// /api/account/library (docs/decisions/accounts.md, "Playlists and settings on the website"): the playlists and
// settings in the account's backup, for the website's Playlists page.
//   GET                           a signed-in browser or device: the playlists (never their passwords or links) and settings
//   POST { op, ... }              a signed-in browser: add, edit or remove a playlist, change settings, ask every
//                                 device to refresh, or edit channel names/logos, favourites and lists
//                                 (lib/library.mjs applyChange). Saved as a new backup version on top of the newest,
//                                 tried again if a device saved at the same moment.
// The website never connects to a provider; each device checks the login when it next refreshes.
import { gunzipSync, gzipSync } from "node:zlib";
import { MAX_BACKUP_BYTES } from "../../lib/accounts.mjs";
import { body, caller, guarded, json } from "../../lib/auth.mjs";
import { applyChange, libraryView } from "../../lib/library.mjs";
import { command } from "../../lib/redis.mjs";
import { backup, saveBackup } from "../../lib/account-store.mjs";

const CHANGES_PER_HOUR = 120;
const TRIES = 3;

async function newest(account) {
    const found = await backup(account, null);
    if (!found) return { version: 0, stored: null };
    try {
        return { version: found.version, stored: JSON.parse(gunzipSync(Buffer.from(found.data, "base64")).toString()) };
    } catch {
        return { version: found.version, stored: null, damaged: true };
    }
}

export function GET(request) {
    return guarded(async () => {
        const who = await caller(request);
        if (!who) return json({ status: "signed out" }, 401);
        const { stored } = await newest(who.account);
        return json(libraryView(stored));
    });
}

export function POST(request) {
    return guarded(async () => {
        const who = await caller(request, { changing: true });
        if (!who) return json({ status: "signed out" }, 401);
        const change = await body(request);
        if (!change || typeof change !== "object") return json({ status: "bad request" }, 400);
        const key = `rl:library:${who.account}`;
        const count = await command("INCR", key);
        if (count === 1) await command("EXPIRE", key, 3600);
        if (count > CHANGES_PER_HOUR) return json({ status: "too many" }, 429);
        for (let i = 0; i < TRIES; i++) {
            const { version, stored, damaged } = await newest(who.account);
            // A damaged backup is left for the next device to replace rather than built on.
            if (damaged) return json({ status: "unavailable" }, 503);
            const result = applyChange(stored, change);
            if (result.error) return json({ status: result.error, field: result.field ?? null }, 400);
            const text = JSON.stringify(result.stored);
            if (Buffer.byteLength(text) > MAX_BACKUP_BYTES) return json({ status: "too large" }, 413);
            const saved = await saveBackup(who.account, version, gzipSync(text).toString("base64"), Buffer.byteLength(text), "Website");
            if (saved != null) return json(libraryView(result.stored));
        }
        return json({ status: "busy" }, 409);
    });
}
