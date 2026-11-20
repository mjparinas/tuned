// Playlists and settings on the website (docs/decisions/accounts.md). Melvin, 2026-11-20: the website should "allow you
// to add a link to m3u, login information, update playlists, add stalker mac address, etc. everything that the android
// app can do, and it should sync with the app and vice versa". The website changes the account's backup (the app's sync
// copy); each device takes the change on its next sync and joins it with its own (app/data/CopyMerge.kt). The website
// never connects to a provider: logins are checked by the app when it next refreshes. Plain functions, so they are
// tested without a server.
import { createHash, randomBytes } from "node:crypto";
import WEB_SETTINGS from "./web-settings.json" with { type: "json" };

export { WEB_SETTINGS };

/** The app's mark for a MAC portal in place of a playlist link (core/Stalker.MARKER). */
const STALKER = "stalker:";
const XTREAM_PATHS = new Set(["get.php", "player_api.php", "xmltv.php", "panel_api.php"]);
const MAX_PLAYLISTS = 50;
const MAX_TEXT = 2048;
const SETTINGS = new Map(WEB_SETTINGS.flatMap((g) => g.settings).map((s) => [s.key, s]));
/** Synced setting that asks every device to download its playlist again (app AppSettings.refreshRequestedAt). */
export const REFRESH_KEY = "refresh_requested_at";

/** The kind of a saved playlist, as the app reads it (Credentials.kind). */
export function kindOf(p) {
    const url = p.playlistUrl ?? "";
    if (!url) return "xtream";
    if (url.startsWith("file:")) return "file";
    if (url === STALKER) return "stalker";
    return "link";
}

/** A playlist's identity, as the app's `samePlaylist`: server, username and link. Hashed, so a link's key stays out of the page. */
export const playlistId = (p) =>
    createHash("sha256")
        .update(`${p.server ?? ""}\n${p.username ?? ""}\n${p.playlistUrl ?? ""}`)
        .digest("hex")
        .slice(0, 16);

const hostOf = (url) => {
    try {
        return new URL(url).host;
    } catch {
        return String(url)
            .replace(/^[a-z]+:\/\//i, "")
            .split(/[/?#]/)[0];
    }
};

/**
 * What the page shows of a playlist. Passwords and links never go back to the browser (a link usually carries the
 * login): only that there is one, so the form can say "leave blank to keep it".
 */
export function playlistView(p) {
    const kind = kindOf(p);
    const detail =
        kind === "xtream"
            ? `${p.username} @ ${hostOf(p.server)}`
            : kind === "link"
              ? hostOf(p.playlistUrl)
              : kind === "stalker"
                ? `${p.username} @ ${hostOf(p.server)}`
                : "";
    return {
        id: playlistId(p),
        kind,
        name: p.name ?? "",
        detail,
        server: kind === "xtream" || kind === "stalker" ? p.server : "",
        username: kind === "xtream" ? p.username : "",
        mac: kind === "stalker" ? p.username : "",
        guide: Boolean(p.guideUrl),
        guideHost: p.guideUrl ? hostOf(p.guideUrl) : "",
        extraGuides: (p.extraGuides ?? []).length,
    };
}

/** A fetch from one of our own pages (never a link opened from elsewhere, another site, or a typed address). */
export const fromOurPage = (headers) => headers.get("sec-fetch-site") === "same-origin";

/**
 * One saved playlist's login, for the provider check on the website (docs/decisions/web.md › Your saved playlists).
 * Only the person's own signed-in browser asks for it, and only to hand it to the check, which talks to the provider
 * from that browser; Tuned's server never contacts the provider. `{ kind, name, server, username, password }` for an
 * Xtream login, `{ kind, name, link }` for an M3U link (both with `guide` and `extraGuides`, for the channel list
 * beside it), or `{ error }`: "gone", or "app only" for a MAC portal or a file,
 * which a browser can't use.
 */
export function checkLogin(stored, id) {
    const p = (stored?.copy?.playlists ?? []).find((x) => playlistId(x) === id);
    if (!p) return { error: "gone" };
    const kind = kindOf(p);
    const name = p.name || playlistView(p).detail;
    const guides = { guide: p.guideUrl ?? "", extraGuides: Array.isArray(p.extraGuides) ? p.extraGuides : [] };
    if (kind === "xtream") return { kind, name, server: p.server, username: p.username, password: p.password ?? "", ...guides };
    if (kind === "link") return { kind, name, link: p.playlistUrl, ...guides };
    return { error: "app only", kind };
}

/** The setting's value in the copy (`{ "b": true }` and so on), or its default. */
function settingValue(settings, s) {
    const stored = settings?.[s.key];
    if (stored && typeof stored === "object") {
        const value = stored.b ?? stored.i ?? stored.s;
        if (s.type === "bool" && typeof value === "boolean") return value;
        if (s.type === "int" && Number.isInteger(value)) return value;
        if (s.type === "choice" && typeof value === "string") return value;
    }
    return s.default;
}

/** Everything the page shows: the playlists, the settings with their values, and when a refresh was last asked for. */
export function libraryView(stored) {
    const copy = stored?.copy ?? {};
    const refresh = copy.settings?.[REFRESH_KEY]?.l;
    return {
        status: "ok",
        exists: Boolean(stored),
        playlists: (copy.playlists ?? []).map(playlistView),
        settings: WEB_SETTINGS.map((g) => ({
            group: g.group,
            settings: g.settings.map((s) => ({ ...s, value: settingValue(copy.settings, s) })),
        })),
        refreshAskedAt: typeof refresh === "number" ? refresh : null,
    };
}

/** The app's XtreamUrls.normalizeServer: trimmed, no trailing slash, http:// when no scheme was typed. */
export function normalizeServer(text) {
    const trimmed = String(text ?? "")
        .trim()
        .replace(/\/+$/, "");
    return /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
}

/** The app's Stalker.normalizeMac: `00:1A:79:AB:CD:EF`, or null when it isn't a MAC address. */
export function normalizeMac(text) {
    const hex = String(text ?? "")
        .trim()
        .replace(/[:\-. ]/g, "");
    if (!/^[0-9a-f]{12}$/i.test(hex)) return null;
    return hex.toUpperCase().match(/../g).join(":");
}

/** The app's Stalker.normalizePortal: the portal address without its page, `/c` or trailing slash. */
export function normalizePortal(text) {
    let url = normalizeServer(text).split("?")[0].split("#")[0];
    const pathStart = url.indexOf("/", url.indexOf("://") + 3);
    if (pathStart >= 0 && url.slice(url.lastIndexOf("/") + 1).includes(".")) url = url.slice(0, url.lastIndexOf("/"));
    url = url.replace(/\/+$/, "");
    if (/\/c$/i.test(url)) url = url.slice(0, -2);
    return url.replace(/\/+$/, "");
}

/**
 * The app's PlaylistLink.parse: a pasted Xtream `get.php` link becomes its login, so catch-up and account status work;
 * any other web address is a plain M3U link. Null when it isn't a web address.
 */
export function parseLink(text) {
    const trimmed = String(text ?? "").trim();
    if (!trimmed || /\s/.test(trimmed)) return null;
    const withScheme = trimmed.includes("://") ? trimmed : `http://${trimmed}`;
    let url;
    try {
        url = new URL(withScheme);
    } catch {
        return null;
    }
    const scheme = url.protocol.replace(":", "").toLowerCase();
    if (scheme !== "http" && scheme !== "https") return null;
    const host = url.hostname;
    if (!host || (!host.includes(".") && host !== "localhost" && !host.includes(":") && !host.startsWith("["))) return null;
    const path = url.pathname;
    const file = path.slice(path.lastIndexOf("/") + 1).toLowerCase();
    if (XTREAM_PATHS.has(file)) {
        const query = new Map([...url.searchParams].map(([k, v]) => [k.toLowerCase(), v]));
        const username = query.get("username") ?? "";
        const password = query.get("password") ?? "";
        if (username && password) {
            const port = url.port ? `:${url.port}` : "";
            return {
                kind: "xtream",
                server: `${scheme}://${url.hostname}${port}${path.slice(0, path.lastIndexOf("/"))}`,
                username,
                password,
            };
        }
    }
    return { kind: "link", url: withScheme };
}

const text = (value) => (typeof value === "string" ? value.trim() : "");
const isWebLink = (value) => {
    try {
        return ["http:", "https:"].includes(new URL(value).protocol);
    } catch {
        return false;
    }
};

/**
 * A saved playlist from the page's form, as the app would save it, or `{ error }` naming the field that is wrong.
 * Editing (`existing`), a blank password, link or guide keeps the saved one, and `clearGuide` removes the guide.
 */
export function buildPlaylist(input, existing = null) {
    if (!input || typeof input !== "object") return { error: "bad request" };
    for (const v of Object.values(input)) if (typeof v === "string" && v.length > MAX_TEXT) return { error: "too long" };
    const name = text(input.name).slice(0, 80);
    const sameKind = existing && kindOf(existing) === input.kind;
    let guideUrl = text(input.guide);
    if (guideUrl && !isWebLink(guideUrl)) return { error: "bad guide", field: "guide" };
    if (!guideUrl && !input.clearGuide && existing) guideUrl = existing.guideUrl ?? "";
    const extraGuides = existing?.extraGuides?.length ? existing.extraGuides : undefined;
    // Shaped as the app writes it: empty optional fields left out, so the same playlist gives the same copy.
    const done = (p) => ({
        playlist: Object.fromEntries(
            Object.entries({ ...p, guideUrl, extraGuides, name }).filter(
                ([k, v]) => v !== undefined && (["server", "username", "password"].includes(k) || v !== ""),
            ),
        ),
    });
    switch (input.kind) {
        case "xtream": {
            const typed = text(input.server);
            if (!typed) return { error: "server needed", field: "server" };
            const server = normalizeServer(typed);
            if (!isWebLink(server) || !parseLink(server)) return { error: "bad server", field: "server" };
            const username = text(input.username);
            if (!username) return { error: "username needed", field: "username" };
            const password =
                typeof input.password === "string" && input.password !== "" ? input.password : sameKind ? existing.password : "";
            if (!password) return { error: "password needed", field: "password" };
            return done({ server, username, password });
        }
        case "link": {
            const typed = text(input.link);
            if (!typed) {
                if (sameKind) return done({ server: "", username: "", password: "", playlistUrl: existing.playlistUrl });
                return { error: "link needed", field: "link" };
            }
            const link = parseLink(typed);
            if (!link) return { error: "bad link", field: "link" };
            if (link.kind === "xtream") return done({ server: link.server, username: link.username, password: link.password });
            return done({ server: "", username: "", password: "", playlistUrl: link.url });
        }
        case "stalker": {
            const typed = text(input.server);
            if (!typed) return { error: "portal needed", field: "server" };
            const server = normalizePortal(typed);
            if (!isWebLink(server) || !parseLink(server)) return { error: "bad portal", field: "server" };
            const mac = normalizeMac(input.mac);
            if (!mac) return { error: "bad mac", field: "mac" };
            return done({ server, username: mac, password: "", playlistUrl: STALKER });
        }
        default:
            return { error: "bad request" };
    }
}

/** A setting's value in the copy's typed form, or null when the setting or value isn't one the website may set. */
export function encodeSetting(key, value) {
    const s = SETTINGS.get(key);
    if (!s) return null;
    if (s.type === "bool") return typeof value === "boolean" ? { b: value } : null;
    const allowed = s.options.map(([v]) => v);
    if (!allowed.includes(value)) return null;
    return s.type === "int" ? { i: value } : { s: value };
}

/**
 * One change from the page applied to the stored backup (`{ fingerprint, changedAt, copy }`, or null when the
 * account has none yet): `{ stored }`, or `{ error, field? }`. The new fingerprint is random, so every device sees a
 * change; `changedAt` is now, so the website's change is the newer one where a device changed the same thing earlier.
 */
export function applyChange(stored, change, now = Date.now()) {
    const copy = structuredClone(stored?.copy ?? { version: 2, partial: true });
    const playlists = Array.isArray(copy.playlists) ? copy.playlists : [];
    const find = (id) => playlists.findIndex((p) => playlistId(p) === id);
    switch (change?.op) {
        case "add": {
            if (playlists.length >= MAX_PLAYLISTS) return { error: "too many playlists" };
            const built = buildPlaylist(change.playlist);
            if (built.error) return built;
            if (playlists.some((p) => playlistId(p) === playlistId(built.playlist))) return { error: "already saved" };
            copy.playlists = [...playlists, built.playlist];
            break;
        }
        case "edit": {
            const at = find(change.id);
            if (at < 0) return { error: "gone" };
            const built = buildPlaylist(change.playlist, playlists[at]);
            if (built.error) return built;
            const id = playlistId(built.playlist);
            if (id !== change.id && playlists.some((p) => playlistId(p) === id)) return { error: "already saved" };
            copy.playlists = playlists.map((p, i) => (i === at ? built.playlist : p));
            break;
        }
        case "remove": {
            const at = find(change.id);
            if (at < 0) return { error: "gone" };
            // A device keeps a playlist it is using when the account has no other, so removing the last one wouldn't stick.
            if (playlists.length === 1) return { error: "last playlist" };
            copy.playlists = playlists.filter((_, i) => i !== at);
            break;
        }
        case "settings": {
            const values = change.values && typeof change.values === "object" ? Object.entries(change.values) : [];
            if (!values.length) return { error: "bad request" };
            const settings = { ...(copy.settings ?? {}) };
            for (const [key, value] of values) {
                const encoded = encodeSetting(key, value);
                if (!encoded) return { error: "bad setting" };
                settings[key] = encoded;
            }
            copy.settings = settings;
            break;
        }
        case "refresh": {
            copy.settings = { ...(copy.settings ?? {}), [REFRESH_KEY]: { l: now } };
            break;
        }
        default:
            return { error: "bad request" };
    }
    return { stored: { fingerprint: `web-${randomBytes(12).toString("hex")}`, changedAt: now, copy } };
}
