// GET /api/account/me: the account as the website and the app show it.
import { caller, guarded, json } from "../../lib/auth.mjs";
import { summary } from "../../lib/account-api.mjs";

export function GET(request) {
    return guarded(async () => {
        const who = await caller(request);
        if (!who) return json({ status: "signed out" }, 401);
        const found = await summary(who.account, who.device?.id ?? null);
        return found ? json(found) : json({ status: "signed out" }, 401);
    });
}
