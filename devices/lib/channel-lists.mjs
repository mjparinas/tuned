// The channel lists the app shares for the website's Channels and guide page (docs/decisions/web.md › The app's copy).
// Melvin, 2026-11-20, chose that "the app shares its list", so the page works with providers that don't let web pages
// read them. Each is a playlist's live channels and next day of guide, gzipped JSON the app builds
// (core ChannelShare): `{ v: 1, playlist, savedAt, channels: [[name, group, logo, guideId, number, link, country,
// adult, id]], guide: { guideId: [[start, stop, title, kind]] } }` (country and adult added 2026-11-21, the stable id
// 2026-11-22, so older apps send shorter rows), links with `{login}`, `{user}` and `{pass}` in place of the login. Only the
// list reaches Tuned: a channel opens in VLC on the person's device, which connects to the provider itself.
// Plain functions, so they are tested without a server.
import { gunzipSync } from "node:zlib";

/** Gzipped, the most the app sends (a Vercel function takes at most 4.5 MB). */
export const MAX_GZIPPED_BYTES = 4_000_000;
/** Unzipped, the most read, so a small upload can't unpack into something huge. */
export const MAX_JSON_BYTES = 64 * 1024 * 1024;
/** Playlists kept per account: the newest few. */
export const KEEP_PLAYLISTS = 10;

const isText = (v) => typeof v === "string";

/**
 * Reads an upload: `{ playlist, channels }` (the playlist's id and how many channels it has), or `{ error }` when it
 * isn't a channel list the website can read.
 */
export function readUpload(gzipped) {
    if (!gzipped?.length) return { error: "bad request" };
    if (gzipped.length > MAX_GZIPPED_BYTES) return { error: "too large" };
    let copy;
    try {
        copy = JSON.parse(gunzipSync(gzipped, { maxOutputLength: MAX_JSON_BYTES }).toString("utf8"));
    } catch (e) {
        return { error: e?.code === "ERR_BUFFER_TOO_LARGE" ? "too large" : "bad request" };
    }
    if (copy?.v !== 1 || !/^[0-9a-f]{16}$/.test(copy.playlist ?? "")) return { error: "bad request" };
    if (!Array.isArray(copy.channels) || !copy.guide || typeof copy.guide !== "object" || Array.isArray(copy.guide))
        return { error: "bad request" };
    const fine = copy.channels.every(
        (c) => Array.isArray(c) && c.length >= 6 && isText(c[0]) && isText(c[1]) && isText(c[2]) && isText(c[3]) && isText(c[5]),
    );
    if (!fine) return { error: "bad request" };
    return { playlist: copy.playlist, channels: copy.channels.length };
}
