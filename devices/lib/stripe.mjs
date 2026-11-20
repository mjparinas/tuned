// Web payments through Stripe (docs/decisions/accounts.md), over its REST API with no client library. Premium and
// extra seats are Stripe prices whose ids are Vercel environment variables; the webhook turns Stripe's events into
// rows in `entitlements`.
import { createHmac, timingSafeEqual } from "node:crypto";
import { PREMIUM_LIFETIME, PREMIUM_YEARLY, SEAT_ONCE, SEAT_YEARLY } from "./accounts.mjs";

/** The environment variable holding each product's Stripe price id. */
export const PRICE_VARIABLES = {
    [PREMIUM_YEARLY]: "STRIPE_PRICE_PREMIUM_YEARLY",
    [PREMIUM_LIFETIME]: "STRIPE_PRICE_PREMIUM_LIFETIME",
    [SEAT_ONCE]: "STRIPE_PRICE_SEAT_ONCE",
    [SEAT_YEARLY]: "STRIPE_PRICE_SEAT_YEARLY",
};
const SUBSCRIPTIONS = [PREMIUM_YEARLY, SEAT_YEARLY];
/** A renewal can land a little after the period ends; a yearly purchase keeps working for these days meanwhile. */
const GRACE_MS = 3 * 86_400_000;
const TOLERANCE_SECONDS = 300;

export const isSubscription = (product) => SUBSCRIPTIONS.includes(product);

/** True when `header` (Stripe-Signature) signs `payload` with `secret`, made within five minutes of `nowSeconds`. */
export function signatureValid(payload, header, secret, nowSeconds) {
    if (!secret || typeof header !== "string") return false;
    const parts = header.split(",").map((p) => p.split("="));
    const time = Number(parts.find(([k]) => k === "t")?.[1]);
    if (!Number.isFinite(time) || Math.abs(nowSeconds - time) > TOLERANCE_SECONDS) return false;
    const expected = Buffer.from(createHmac("sha256", secret).update(`${time}.${payload}`).digest("hex"));
    return parts
        .filter(([k]) => k === "v1")
        .some(([, v]) => {
            const given = Buffer.from(v ?? "");
            return given.length === expected.length && timingSafeEqual(given, expected);
        });
}

const ACTIVE_SUBSCRIPTION = ["active", "trialing", "past_due"];

/**
 * What one Stripe event changes: `{ entitlement }` to save (keyed by source and ref), `{ refund }` to mark a payment
 * refunded, and `customer` to remember for the billing portal. Events we don't use give `null`.
 */
export function eventChange(event) {
    const object = event?.data?.object ?? {};
    const meta = object.metadata ?? {};
    switch (event?.type) {
        case "checkout.session.completed": {
            if (object.mode !== "payment" || object.payment_status !== "paid") {
                return object.customer && meta.account ? { customer: { account: meta.account, id: object.customer } } : null;
            }
            if (!meta.account || !meta.product || !object.payment_intent) return null;
            return {
                customer: object.customer ? { account: meta.account, id: object.customer } : null,
                entitlement: {
                    source: "stripe",
                    ref: object.payment_intent,
                    account: meta.account,
                    product: meta.product,
                    quantity: Math.max(1, Number(meta.quantity) || 1),
                    status: "active",
                    expiresAt: null,
                },
            };
        }
        case "customer.subscription.created":
        case "customer.subscription.updated":
        case "customer.subscription.deleted": {
            if (!meta.account || !meta.product) return null;
            const item = object.items?.data?.[0] ?? {};
            const end = item.current_period_end ?? object.current_period_end;
            const ended = event.type === "customer.subscription.deleted" || !ACTIVE_SUBSCRIPTION.includes(object.status);
            return {
                customer: object.customer ? { account: meta.account, id: object.customer } : null,
                entitlement: {
                    source: "stripe",
                    ref: object.id,
                    account: meta.account,
                    product: meta.product,
                    quantity: Math.max(1, Number(item.quantity) || 1),
                    status: ended ? "expired" : "active",
                    expiresAt: end ? end * 1000 + GRACE_MS : null,
                },
            };
        }
        case "charge.refunded":
            return object.refunded && object.payment_intent ? { refund: { source: "stripe", ref: object.payment_intent } } : null;
        default:
            return null;
    }
}

/** The form fields of a Checkout Session for `product` × `quantity`, bought by `account`. */
export function checkoutFields({ product, quantity, price, account, email, customer, origin, tax }) {
    const meta = { account, product, quantity: String(quantity) };
    const fields = {
        mode: isSubscription(product) ? "subscription" : "payment",
        "line_items[0][price]": price,
        "line_items[0][quantity]": String(quantity),
        client_reference_id: account,
        success_url: `${origin}/account?bought=1`,
        cancel_url: `${origin}/account`,
    };
    for (const [k, v] of Object.entries(meta)) {
        fields[`metadata[${k}]`] = v;
        if (isSubscription(product)) fields[`subscription_data[metadata][${k}]`] = v;
    }
    if (customer) fields.customer = customer;
    else if (email) fields.customer_email = email;
    if (tax) {
        fields["automatic_tax[enabled]"] = "true";
        // Stripe Tax needs the buyer's address; a returning customer's is taken from what they type at checkout.
        if (customer) fields["customer_update[address]"] = "auto";
    }
    return fields;
}

/** One call to Stripe's API with the secret key. */
export async function stripe(method, path, fields) {
    const key = process.env.STRIPE_SECRET_KEY;
    if (!key) throw new Error("STRIPE_SECRET_KEY isn't set");
    const response = await fetch(`https://api.stripe.com/v1/${path}`, {
        method,
        headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/x-www-form-urlencoded" },
        body: fields ? new URLSearchParams(fields) : undefined,
    });
    const body = await response.json();
    if (!response.ok) throw new Error(`Stripe answered ${response.status}: ${body?.error?.code ?? ""}`);
    return body;
}
