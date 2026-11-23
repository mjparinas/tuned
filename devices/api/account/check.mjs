// GET /api/account/check?id=<playlist id> (docs/decisions/web.md › Your saved playlists): one saved playlist's login,
// for Channels and guide (always on), the provider check at /check, and the web player at /watch. Melvin, 2026-11-20:
// "if you're logged in, /check should just let you choose the playlist to try". Only for the person's own signed-in
// browser, asking from our own page. The browser then talks to the provider; Tuned never contacts it. Always on so
// Channels and guide can fill stream links for VLC even when the web_player flag is off; /check and /watch stay behind
// that flag on their own pages.
import { gunzipSync } from "node:zlib";
import { guarded, json, webAccount } from "../../lib/auth.mjs";
import { checkLogin, fromOurPage } from "../../lib/library.mjs";
import { command } from "../../lib/redis.mjs";
import { backup } from "../../lib/account-store.mjs";

const READS_PER_HOUR = 60;

export function GET(request) {
    return guarded(async () => {
        if (!fromOurPage(request.headers)) return json({ status: "signed out" }, 401);
        const account = await webAccount(request);
        if (!account) return json({ status: "signed out" }, 401);
        const key = `rl:check:${account}`;
        const count = await command("INCR", key);
        if (count === 1) await command("EXPIRE", key, 3600);
        if (count > READS_PER_HOUR) return json({ status: "too many" }, 429);
        const id = new URL(request.url).searchParams.get("id") ?? "";
        if (!/^[0-9a-f]{16}$/.test(id)) return json({ status: "bad request" }, 400);
        const found = await backup(account, null);
        if (!found) return json({ status: "gone" }, 404);
        const stored = JSON.parse(gunzipSync(Buffer.from(found.data, "base64")).toString());
        const login = checkLogin(stored, id);
        if (login.error) return json({ status: login.error, kind: login.kind ?? null }, login.error === "gone" ? 404 : 400);
        return json({ status: "ok", ...login });
    });
}
