import { test } from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { checkoutFields, eventChange, signatureValid } from "./stripe.mjs";

const sign = (payload, secret, time) => `t=${time},v1=${createHmac("sha256", secret).update(`${time}.${payload}`).digest("hex")}`;

test("a webhook is taken only when Stripe signed it recently with our secret", () => {
    const payload = '{"id":"evt_1"}';
    assert.equal(signatureValid(payload, sign(payload, "whsec", 1000), "whsec", 1100), true);
    assert.equal(signatureValid(payload, sign(payload, "other", 1000), "whsec", 1100), false);
    assert.equal(signatureValid(payload, sign(payload, "whsec", 1000), "whsec", 2000), false);
    assert.equal(signatureValid(payload + " ", sign(payload, "whsec", 1000), "whsec", 1000), false);
    assert.equal(signatureValid(payload, undefined, "whsec", 1000), false);
});

test("a paid one-time checkout becomes a lasting entitlement with its quantity", () => {
    const change = eventChange({
        type: "checkout.session.completed",
        data: {
            object: {
                mode: "payment",
                payment_status: "paid",
                payment_intent: "pi_1",
                customer: "cus_1",
                metadata: { account: "acc", product: "seat_once", quantity: "2" },
            },
        },
    });
    assert.deepEqual(change.entitlement, {
        source: "stripe",
        ref: "pi_1",
        account: "acc",
        product: "seat_once",
        quantity: 2,
        status: "active",
        expiresAt: null,
    });
    assert.deepEqual(change.customer, { account: "acc", id: "cus_1" });
});

test("a subscription runs to its period end plus a few days, and ends when cancelled", () => {
    const sub = (type, status) => ({
        type,
        data: {
            object: {
                id: "sub_1",
                status,
                customer: "cus_1",
                items: { data: [{ quantity: 3, current_period_end: 1000 }] },
                metadata: { account: "acc", product: "seat_yearly" },
            },
        },
    });
    const active = eventChange(sub("customer.subscription.updated", "active")).entitlement;
    assert.equal(active.status, "active");
    assert.equal(active.quantity, 3);
    assert.equal(active.expiresAt, 1000 * 1000 + 3 * 86_400_000);
    assert.equal(eventChange(sub("customer.subscription.deleted", "canceled")).entitlement.status, "expired");
    assert.equal(eventChange(sub("customer.subscription.updated", "unpaid")).entitlement.status, "expired");
});

test("a full refund marks the payment refunded; other events change nothing", () => {
    assert.deepEqual(eventChange({ type: "charge.refunded", data: { object: { refunded: true, payment_intent: "pi_1" } } }), {
        refund: { source: "stripe", ref: "pi_1" },
    });
    assert.equal(eventChange({ type: "charge.refunded", data: { object: { refunded: false, payment_intent: "pi_1" } } }), null);
    assert.equal(eventChange({ type: "invoice.paid", data: { object: {} } }), null);
});

test("checkout carries the account on the session and on a subscription", () => {
    const yearly = checkoutFields({
        product: "seat_yearly",
        quantity: 2,
        price: "price_1",
        account: "acc",
        email: "a@b.c",
        origin: "https://t.example",
        tax: true,
    });
    assert.equal(yearly.mode, "subscription");
    assert.equal(yearly["subscription_data[metadata][account]"], "acc");
    assert.equal(yearly["line_items[0][quantity]"], "2");
    assert.equal(yearly.customer_email, "a@b.c");
    assert.equal(yearly["automatic_tax[enabled]"], "true");
    const once = checkoutFields({
        product: "premium_lifetime",
        quantity: 1,
        price: "price_2",
        account: "acc",
        customer: "cus_1",
        origin: "https://t.example",
    });
    assert.equal(once.mode, "payment");
    assert.equal(once.customer, "cus_1");
    assert.equal(once["subscription_data[metadata][account]"], undefined);
});
