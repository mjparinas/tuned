// Remote flags (docs/decisions/admin.md): switches the app reads from GET /api/flags, set on the admin's Flags tab
// for everyone or for a share of installs. Mostly off-switches for features that lean on someone else's service, so a
// broken or over-limit service can be switched off without a release.
import { createHash } from "node:crypto";

export const FLAGS = [
    { key: "tmdb", label: "Posters, ratings and episode names from TMDB", group: "Services", type: "bool", default: true },
    { key: "subtitle_search", label: "Subtitle search (OpenSubtitles)", group: "Services", type: "bool", default: true },
    { key: "trakt", label: "Trakt sign-in and scrobbling", group: "Services", type: "bool", default: true },
    { key: "tv_sign_in", label: "Sign in on a TV with your phone", group: "Services", type: "bool", default: true },
    { key: "account_backup", label: "Account backups", group: "Services", type: "bool", default: true },
    { key: "auto_tune", label: "Auto-tune for live events", group: "Features", type: "bool", default: true },
    { key: "channel_scan", label: "Channel scan", group: "Features", type: "bool", default: true },
    { key: "downloads", label: "Downloads", group: "Features", type: "bool", default: true },
    { key: "cast", label: "Cast to a TV", group: "Features", type: "bool", default: true },
    {
        key: "usage_batch_minutes",
        label: "Minutes between usage stats uploads",
        group: "App",
        type: "int",
        default: 60,
        min: 15,
        max: 1440,
    },
    {
        key: "min_version_code",
        label: "Oldest version that may run without updating (0 is any)",
        group: "App",
        type: "int",
        default: 0,
        min: 0,
        max: 2_000_000_000,
    },
];

const BY_KEY = Object.fromEntries(FLAGS.map((f) => [f.key, f]));

/** A value an admin typed, checked against the flag's type, or undefined. */
export function checkValue(key, value) {
    const flag = BY_KEY[key];
    if (!flag) return undefined;
    if (flag.type === "bool") return typeof value === "boolean" ? value : undefined;
    if (flag.type === "int") return Number.isInteger(value) && value >= flag.min && value <= flag.max ? value : undefined;
    return undefined;
}

/** 0 to 99, stable for a flag and an install, so raising the share only adds installs. */
export const bucket = (key, install) => parseInt(createHash("sha256").update(`${key}:${install}`).digest("hex").slice(0, 8), 16) % 100;

/**
 * What one install sees: every flag's default, replaced by an admin's value when the install falls in its share.
 * `rows` are `{ key, value, share }` (share 0 to 100).
 */
export function flagsFor(rows, install) {
    const out = Object.fromEntries(FLAGS.map((f) => [f.key, f.default]));
    for (const row of rows) {
        if (!BY_KEY[row.key] || checkValue(row.key, row.value) === undefined) continue;
        const share = row.share ?? 100;
        if (share >= 100 || (install && bucket(row.key, install) < share)) out[row.key] = row.value;
    }
    return out;
}
