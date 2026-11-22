// /api/backup (docs/decisions/accounts.md): the account's backup, the app's sync copy as JSON, not encrypted
// (Melvin, 2026-11-19: "i dont want to encrypt backups"). Kept gzipped in Postgres, the last few versions.
//   GET            a signed-in device: the newest copy (or `?version=N`), with its version in X-Backup-Version and ETag
//                  (Vercel weakens the ETag of a compressed answer to W/"N", so the app reads X-Backup-Version first)
//   PUT            a signed-in device: a new copy on top of the version in If-Match ("0" for the first);
//                  409 with the newest version when another device saved since
//   DELETE         the website or a device: deletes every version
import { gunzipSync, gzipSync } from "node:zlib";
import { baseVersion, MAX_BACKUP_BYTES } from "../lib/accounts.mjs";
import { caller, deviceCaller, guarded, json } from "../lib/auth.mjs";
import { command } from "../lib/redis.mjs";
import { backup, backupInfo, deleteBackups, saveBackup } from "../lib/account-store.mjs";

const UPLOADS_PER_HOUR = 120;

export function GET(request) {
    return guarded(async () => {
        const device = await deviceCaller(request);
        if (!device) return json({ status: "signed out" }, 401);
        const asked = new URL(request.url).searchParams.get("version");
        const found = await backup(device.account, asked == null ? null : baseVersion(asked));
        if (!found) return json({ status: "none" }, 404);
        return new Response(gunzipSync(Buffer.from(found.data, "base64")), {
            headers: {
                "Content-Type": "application/json",
                "Cache-Control": "no-store",
                ETag: `"${found.version}"`,
                "X-Backup-Version": String(found.version),
            },
        });
    });
}

export function PUT(request) {
    return guarded(async () => {
        const device = await deviceCaller(request);
        if (!device) return json({ status: "signed out" }, 401);
        const base = baseVersion(request.headers.get("if-match"));
        if (base == null) return json({ status: "bad request" }, 400);
        const text = await request.text();
        if (text.length === 0 || Buffer.byteLength(text) > MAX_BACKUP_BYTES) return json({ status: "too large" }, 413);
        try {
            JSON.parse(text);
        } catch {
            return json({ status: "bad request" }, 400);
        }
        const key = `rl:backup:${device.account}`;
        const count = await command("INCR", key);
        if (count === 1) await command("EXPIRE", key, 3600);
        if (count > UPLOADS_PER_HOUR) return json({ status: "too many" }, 429);
        const data = gzipSync(text).toString("base64");
        const version = await saveBackup(device.account, base, data, Buffer.byteLength(text), device.name);
        if (version == null) {
            const newest = (await backupInfo(device.account))[0];
            return json({ status: "conflict", version: newest?.version ?? 0 }, 409);
        }
        return json({ status: "ok", version }, 200, { ETag: `"${version}"`, "X-Backup-Version": String(version) });
    });
}

export function DELETE(request) {
    return guarded(async () => {
        const who = await caller(request, { changing: true });
        if (!who) return json({ status: "signed out" }, 401);
        await deleteBackups(who.account);
        return json({ status: "ok" });
    });
}
