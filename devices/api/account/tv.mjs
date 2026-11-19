// POST /api/account/tv { code }: the website (or a signed-in phone) signing in the TV that shows `code`. The TV
// gets a one-time sign-in code the next time it asks the relay, and nothing else.
import { body, caller, guarded, json, newLink, overLimit } from "../../lib/auth.mjs";
import { command } from "../../lib/redis.mjs";
import { normalizeCode, tvLinkKey, tvEntryKey } from "../../lib/tv.mjs";

export function POST(request) {
    return guarded(async () => {
        const who = await caller(request, { changing: true });
        if (!who) return json({ status: "signed out" }, 401);
        const code = normalizeCode((await body(request))?.code);
        if (!code) return json({ status: "bad code" }, 400);
        if (await overLimit(request, "tvlink", 20)) return json({ status: "too many" }, 429);
        const left = await command("TTL", tvEntryKey(code));
        if (!(left > 0)) return json({ status: "not found" }, 404);
        const link = await newLink(who.account, null, left);
        const saved = await command("SET", tvLinkKey(code), link, "EX", left, "NX");
        return saved === "OK" ? json({ status: "ok" }) : json({ status: "already sent" }, 409);
    });
}
