// POST /api/stripe/checkout { product, quantity }: the website starting a Stripe Checkout for Premium or extra
// seats. Seats must match the kind of Premium (lifetime takes one-time seats, yearly takes yearly ones), and an
// account has at most ten seats. Answers `{ url }` to send the browser to.
import { checkoutAllowed, seatPlan } from "../../lib/accounts.mjs";
import { body, guarded, json, webAccount } from "../../lib/auth.mjs";
import { account, entitlements } from "../../lib/account-store.mjs";
import { sameSite } from "../../lib/accounts.mjs";
import { checkoutFields, PRICE_VARIABLES, stripe } from "../../lib/stripe.mjs";

export function POST(request) {
    return guarded(async () => {
        if (!sameSite(request)) return json({ status: "bad request" }, 400);
        const accountId = await webAccount(request);
        if (!accountId) return json({ status: "signed out" }, 401);
        const ask = await body(request);
        const product = ask?.product;
        const quantity = Number(ask?.quantity ?? 1);
        const plan = seatPlan(await entitlements(accountId), Date.now());
        const allowed = checkoutAllowed(plan, product, quantity);
        if (allowed !== "ok") return json({ status: allowed }, 400);
        const price = process.env[PRICE_VARIABLES[product]];
        if (!price || !process.env.STRIPE_SECRET_KEY) return json({ status: "not for sale" }, 503);
        const owner = await account(accountId);
        const session = await stripe(
            "POST",
            "checkout/sessions",
            checkoutFields({
                product,
                quantity,
                price,
                account: accountId,
                email: owner.email,
                customer: owner.stripeCustomer,
                origin: new URL(request.url).origin,
                tax: process.env.STRIPE_TAX === "1",
            }),
        );
        return json({ status: "ok", url: session.url });
    });
}
