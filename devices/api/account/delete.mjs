// POST /api/account/delete: deletes the account with its devices, purchases' records and backups, and cancels any
// Stripe subscription. A Play subscription is cancelled in Play, which the page links to.
import { caller, endSession, guarded, json } from "../../lib/auth.mjs";
import { account, deleteAccount, entitlements } from "../../lib/account-store.mjs";
import { stripe } from "../../lib/stripe.mjs";

export function POST(request) {
    return guarded(async () => {
        const who = await caller(request, { changing: true });
        if (!who) return json({ status: "signed out" }, 401);
        const owner = await account(who.account);
        if (owner?.stripeCustomer) {
            for (const e of await entitlements(who.account)) {
                if (e.source === "stripe" && e.ref.startsWith("sub_") && e.status === "active")
                    await stripe("DELETE", `subscriptions/${e.ref}`);
            }
        }
        await deleteAccount(who.account);
        return json({ status: "ok" }, 200, who.device ? {} : { "Set-Cookie": await endSession(request) });
    });
}
