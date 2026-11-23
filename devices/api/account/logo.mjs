// GET /api/account/logo?u=<http logo> (docs/decisions/web.md › Logos): a channel's logo for the website's Channels
// and guide page, which as an https page can't show an http picture itself. Only for a signed-in browser on our own
// page (same gate as Channels and guide — always on). Pictures only (lib/logo-relay.mjs): never a stream, a playlist or a login.
import { guarded, json, webAccount } from "../../lib/auth.mjs";
import { fromOurPage } from "../../lib/library.mjs";
import { fetchLogo } from "../../lib/logo-relay.mjs";
import { command } from "../../lib/redis.mjs";

/** A guide of a few thousand channels, opened a few times. */
const LOGOS_PER_HOUR = 6000;
const WEEK = 7 * 86_400;

export function GET(request) {
    return guarded(async () => {
        if (!fromOurPage(request.headers)) return json({ status: "signed out" }, 401);
        const account = await webAccount(request);
        if (!account) return json({ status: "signed out" }, 401);
        const key = `rl:logo:${account}`;
        const count = await command("INCR", key);
        if (count === 1) await command("EXPIRE", key, 3600);
        if (count > LOGOS_PER_HOUR) return json({ status: "too many" }, 429);
        const got = await fetchLogo(new URL(request.url).searchParams.get("u"));
        if (got.error) {
            const status = got.error === "bad request" || got.error === "refused" ? 400 : got.error === "too large" ? 413 : 502;
            // A logo that's missing stays missing for a day, so the page doesn't ask again on every visit.
            return json({ status: got.error }, status, { "Cache-Control": "private, max-age=86400" });
        }
        return new Response(got.bytes, {
            headers: {
                "Content-Type": got.type,
                "Cache-Control": `private, max-age=${WEEK}`,
                "X-Content-Type-Options": "nosniff",
                "Content-Security-Policy": "default-src 'none'; sandbox",
            },
        });
    });
}
