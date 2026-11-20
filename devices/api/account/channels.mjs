// /api/account/channels (docs/decisions/web.md › The app's copy): the channel list and guide each playlist's app shares,
// for the website's Channels and guide page, used where the provider doesn't let web pages read it.
//   PUT            a signed-in device: a playlist's list (gzipped JSON from core ChannelShare), replacing its last one.
//                  Always on (Melvin, 2026-11-20): every signed-in app keeps its list here, not only for the website.
//   GET ?playlist  a signed-in device, always (it fills Live TV from the copy when the provider can't be reached), or
//                  the person's own signed-in browser, from our own page, while web_player is on: that list, gzipped
// Only the list reaches Tuned. A channel opens in VLC on the person's device, which connects to the provider itself.
import { deviceCaller, guarded, json, webAccount } from "../../lib/auth.mjs";
import { flagRows } from "../../lib/admin/data.mjs";
import { flagsFor } from "../../lib/admin/flags.mjs";
import { fromOurPage } from "../../lib/library.mjs";
import { KEEP_PLAYLISTS, MAX_GZIPPED_BYTES, readUpload } from "../../lib/channel-lists.mjs";
import { command } from "../../lib/redis.mjs";
import { channelList, saveChannelList } from "../../lib/account-store.mjs";

const UPLOADS_PER_HOUR = 30;
const READS_PER_HOUR = 120;

async function on() {
    if (process.env.VERCEL_ENV && process.env.VERCEL_ENV !== "production") return true;
    return flagsFor(await flagRows(), null).web_player === true;
}

async function limited(key, max) {
    const count = await command("INCR", key);
    if (count === 1) await command("EXPIRE", key, 3600);
    return count > max;
}

export function PUT(request) {
    return guarded(async () => {
        const device = await deviceCaller(request);
        if (!device) return json({ status: "signed out" }, 401);
        if (Number(request.headers.get("content-length") ?? 0) > MAX_GZIPPED_BYTES) return json({ status: "too large" }, 413);
        if (await limited(`rl:channels:${device.account}`, UPLOADS_PER_HOUR)) return json({ status: "too many" }, 429);
        const body = Buffer.from(await request.arrayBuffer());
        const read = readUpload(body);
        if (read.error) return json({ status: read.error }, read.error === "too large" ? 413 : 400);
        await saveChannelList(
            device.account,
            read.playlist,
            body.toString("base64"),
            body.length,
            read.channels,
            device.name,
            KEEP_PLAYLISTS,
        );
        return json({ status: "ok", channels: read.channels });
    });
}

export function GET(request) {
    return guarded(async () => {
        // A signed-in device reads its playlist's copy whatever the flag: a new or reset device fills Live TV from it
        // when the provider can't be reached (Melvin, 2026-11-20). A browser needs our own page and the flag.
        const device = await deviceCaller(request);
        let account = device?.account ?? null;
        if (!device) {
            if (!fromOurPage(request.headers)) return json({ status: "signed out" }, 401);
            account = await webAccount(request);
            if (!account) return json({ status: "signed out" }, 401);
            if (!(await on())) return json({ status: "off" }, 404);
        }
        if (await limited(`rl:channels-read:${account}`, READS_PER_HOUR)) return json({ status: "too many" }, 429);
        const id = new URL(request.url).searchParams.get("playlist") ?? "";
        if (!/^[0-9a-f]{16}$/.test(id)) return json({ status: "bad request" }, 400);
        const found = await channelList(account, id);
        if (!found) return json({ status: "none" }, 404);
        // Sent as saved, gzipped: the browser unpacks it. Who sent it and when ride along in headers.
        return new Response(Buffer.from(found.data, "base64"), {
            headers: {
                "Content-Type": "application/json",
                "Content-Encoding": "gzip",
                "Cache-Control": "private, no-store",
                "X-Saved-At": String(found.savedAt ?? ""),
                "X-Saved-By": encodeURIComponent(found.device ?? ""),
            },
        });
    });
}
