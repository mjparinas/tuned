// POST /api/stripe/portal: Stripe's billing page for the signed-in account (receipts, card, cancelling a yearly plan).
import { guarded, json, webAccount } from "../../lib/auth.mjs";
import { sameSite } from "../../lib/accounts.mjs";
import { account } from "../../lib/account-store.mjs";
import { stripe } from "../../lib/stripe.mjs";

export function POST(request) {
    return guarded(async () => {
        if (!sameSite(request)) return json({ status: "bad request" }, 400);
        const accountId = await webAccount(request);
        if (!accountId) return json({ status: "signed out" }, 401);
        const owner = await account(accountId);
        if (!owner?.stripeCustomer) return json({ status: "none" }, 404);
        const origin = new URL(request.url).origin;
        const portal = await stripe("POST", "billing_portal/sessions", { customer: owner.stripeCustomer, return_url: `${origin}/account` });
        return json({ status: "ok", url: portal.url });
    });
}
