// GET /api/account/email: whether this account gets the daily Getting to know Tuned emails. POST { tips: boolean }
// turns them on or off (docs/decisions/emails.md). The account page's Emails switch uses it.
import { body, caller, guarded, json } from "../../lib/auth.mjs";
import { setTips, tipsOn } from "../../lib/email-store.mjs";

export function GET(request) {
    return guarded(async () => {
        const who = await caller(request);
        if (!who) return json({ status: "signed out" }, 401);
        return json({ status: "ok", tips: await tipsOn(who.account) });
    });
}

export function POST(request) {
    return guarded(async () => {
        const who = await caller(request, { changing: true });
        if (!who) return json({ status: "signed out" }, 401);
        const ask = await body(request);
        if (typeof ask?.tips !== "boolean") return json({ status: "bad request" }, 400);
        await setTips(who.account, ask.tips);
        return json({ status: "ok", tips: ask.tips });
    });
}
