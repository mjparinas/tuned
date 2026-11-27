// Playlists and settings on the website (docs/decisions/accounts.md). Melvin, 2026-11-20: the website should "allow you
// to add a link to m3u, login information, update playlists, add stalker mac address, etc. everything that the android
// app can do, and it should sync with the app and vice versa". The website changes the account's backup (the app's sync
// copy); each device takes the change on its next sync and joins it with its own (app/data/CopyMerge.kt). The website
// never connects to a provider: logins are checked by the app when it next refreshes. Plain functions, so they are
// tested without a server.
import { createHash, randomBytes } from "node:crypto";
import WEB_SETTINGS from "./web-settings.json" with { type: "json" };
import { byId, idText, sameId, wholeId } from "./whole-json.mjs";

export { WEB_SETTINGS };

/** The app's mark for a MAC portal in place of a playlist link (core/Stalker.MARKER). */
const STALKER = "stalker:";
/** Markers for Plex / Jellyfin / Emby (core/MediaServer.Product.marker). */
const MEDIA = { "plex:": "plex", "jellyfin:": "jellyfin", "emby:": "emby" };
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
    if (MEDIA[url]) return MEDIA[url];
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
        kind === "xtream" || kind === "jellyfin" || kind === "emby"
            ? `${p.username} @ ${hostOf(p.server)}`
            : kind === "link"
              ? hostOf(p.playlistUrl)
              : kind === "stalker" || kind === "plex"
                ? hostOf(p.server)
                : "";
    return {
        id: playlistId(p),
        kind,
        name: p.name ?? "",
        detail,
        server: ["xtream", "stalker", "plex", "jellyfin", "emby"].includes(kind) ? p.server : "",
        username: kind === "xtream" || kind === "jellyfin" || kind === "emby" ? p.username : "",
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

/** A profile as the channels page manages favourites and lists for it. */
function profileView(p) {
    return {
        id: typeof wholeId(p.id) === "bigint" ? idText(p.id) : p.id,
        name: String(p.name ?? "You"),
        favourites: (p.favourites ?? []).map((f) => idText(f.itemId)).filter(Boolean),
        lists: (p.lists ?? []).map((l) => ({
            id: idText(l.id),
            name: String(l.name ?? ""),
            items: (l.items ?? []).map((i) => idText(i.itemId)).filter(Boolean),
        })),
    };
}

/** Everything the page shows: the playlists, the settings with their values, and when a refresh was last asked for. */
export function libraryView(stored) {
    const copy = stored?.copy ?? {};
    const refresh = copy.settings?.[REFRESH_KEY]?.l;
    let profiles = Array.isArray(copy.profiles) ? copy.profiles.map(profileView) : [];
    // Older copies kept favourites and lists at the top level; the channels page still shows them as one profile.
    if (!profiles.length && (copy.favourites?.length || copy.lists?.length)) {
        profiles = [
            profileView({
                id: 1,
                name: "You",
                favourites: copy.favourites,
                lists: copy.lists,
            }),
        ];
    }
    const edits = {};
    for (const e of copy.channelEdits ?? []) {
        const key = idText(e?.itemId);
        if (!key) continue;
        edits[key] = { name: e.name ?? null, logo: e.logo ?? null };
    }
    return {
        status: "ok",
        exists: Boolean(stored),
        playlists: (copy.playlists ?? []).map(playlistView),
        settings: WEB_SETTINGS.map((g) => ({
            group: g.group,
            settings: g.settings.map((s) => ({ ...s, value: settingValue(copy.settings, s) })),
        })),
        refreshAskedAt: typeof refresh === "number" ? refresh : null,
        profiles,
        channelEdits: edits,
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
        case "plex": {
            const typed = text(input.server);
            if (!typed) return { error: "server needed", field: "server" };
            const server = normalizeServer(typed);
            if (!isWebLink(server) || !parseLink(server)) return { error: "bad server", field: "server" };
            const password =
                typeof input.password === "string" && input.password !== "" ? input.password : sameKind ? existing.password : "";
            if (!password) return { error: "password needed", field: "password" };
            return done({ server, username: "", password, playlistUrl: "plex:" });
        }
        case "jellyfin":
        case "emby": {
            const typed = text(input.server);
            if (!typed) return { error: "server needed", field: "server" };
            const server = normalizeServer(typed);
            if (!isWebLink(server) || !parseLink(server)) return { error: "bad server", field: "server" };
            const username = text(input.username);
            if (!username) return { error: "username needed", field: "username" };
            const password =
                typeof input.password === "string" && input.password !== "" ? input.password : sameKind ? existing.password : "";
            if (!password) return { error: "password needed", field: "password" };
            return done({ server, username, password, playlistUrl: `${input.kind}:` });
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
        case "channel_edit": {
            const edited = applyChannelEdit(copy, change);
            if (edited.error) return edited;
            break;
        }
        case "favourite": {
            const fav = applyFavourite(copy, change, now);
            if (fav.error) return fav;
            break;
        }
        case "list_add": {
            const added = applyListAdd(copy, change, now);
            if (added.error) return added;
            break;
        }
        case "list_rename": {
            const renamed = applyListRename(copy, change);
            if (renamed.error) return renamed;
            break;
        }
        case "list_delete": {
            const deleted = applyListDelete(copy, change);
            if (deleted.error) return deleted;
            break;
        }
        case "list_item": {
            const item = applyListItem(copy, change, now);
            if (item.error) return item;
            break;
        }
        default:
            return { error: "bad request" };
    }
    return { stored: { fingerprint: `web-${randomBytes(12).toString("hex")}`, changedAt: now, copy } };
}

const MAX_NAME = 60;
// Ids are the app's Longs, often past 2^53 and often negative, so they stay whole (lib/whole-json.mjs).
const itemIdOf = wholeId;
const trimName = (v) =>
    String(v ?? "")
        .trim()
        .slice(0, MAX_NAME);

/** The profile [change.profileId] lives on, creating a default one when the backup has none yet. */
function ensureProfile(copy, profileId, now) {
    if (!Array.isArray(copy.profiles)) copy.profiles = [];
    if (!copy.profiles.length) {
        copy.profiles.push({
            id: 1,
            name: "You",
            colour: 0,
            kids: false,
            createdAt: now,
            favourites: Array.isArray(copy.favourites) ? copy.favourites : [],
            lists: Array.isArray(copy.lists) ? copy.lists : [],
        });
        delete copy.favourites;
        delete copy.lists;
        delete copy.partial;
    }
    const id = itemIdOf(profileId) ?? copy.profiles[0].id;
    const profile = copy.profiles.find((p) => sameId(p.id, id));
    if (!profile) return { error: "gone" };
    if (!Array.isArray(profile.favourites)) profile.favourites = [];
    if (!Array.isArray(profile.lists)) profile.lists = [];
    return { profile };
}

/** Rename and/or change logo for one channel (docs/decisions/lists.md); blank clears that side. */
function applyChannelEdit(copy, change) {
    const itemId = itemIdOf(change.itemId);
    if (!itemId) return { error: "bad request" };
    if (!("name" in change) && !("logo" in change)) return { error: "bad request" };
    const name = "name" in change ? (change.name == null || change.name === "" ? null : trimName(change.name) || null) : undefined;
    const logo =
        "logo" in change
            ? change.logo == null || change.logo === ""
                ? null
                : String(change.logo).trim().slice(0, MAX_TEXT) || null
            : undefined;
    if (name !== undefined && name !== null && !name) return { error: "bad request", field: "name" };
    if (logo !== undefined && logo !== null && !/^https?:\/\//i.test(logo)) return { error: "bad logo", field: "logo" };
    const edits = Array.isArray(copy.channelEdits) ? [...copy.channelEdits] : [];
    const at = edits.findIndex((e) => sameId(e.itemId, itemId));
    const prev = at >= 0 ? edits[at] : { itemId, name: null, logo: null };
    const next = {
        itemId,
        name: name !== undefined ? name : (prev.name ?? null),
        logo: logo !== undefined ? logo : (prev.logo ?? null),
    };
    if (!next.name && !next.logo) {
        copy.channelEdits = edits.filter((e) => !sameId(e.itemId, itemId));
    } else if (at >= 0) {
        edits[at] = next;
        copy.channelEdits = edits;
    } else {
        copy.channelEdits = [...edits, next];
    }
    delete copy.partial;
    return {};
}

function applyFavourite(copy, change, now) {
    const itemId = itemIdOf(change.itemId);
    if (!itemId || typeof change.on !== "boolean") return { error: "bad request" };
    const found = ensureProfile(copy, change.profileId, now);
    if (found.error) return found;
    const { profile } = found;
    const rest = profile.favourites.filter((f) => !sameId(f.itemId, itemId));
    if (change.on) {
        const position = rest.reduce((m, f) => Math.max(m, f.position ?? 0), 0) + 1;
        profile.favourites = [...rest, { itemId, addedAt: now, position }].sort((a, b) => byId(a.itemId, b.itemId));
    } else {
        profile.favourites = rest;
    }
    return {};
}

function applyListAdd(copy, change, now) {
    const name = trimName(change.name);
    if (!name) return { error: "bad request", field: "name" };
    const found = ensureProfile(copy, change.profileId, now);
    if (found.error) return found;
    const { profile } = found;
    if (profile.lists.length >= 50) return { error: "too many lists" };
    const id = Number(BigInt("0x" + randomBytes(7).toString("hex")) % BigInt(Number.MAX_SAFE_INTEGER - 1)) + 1;
    const position = profile.lists.reduce((m, l) => Math.max(m, l.position ?? 0), 0) + 1;
    profile.lists = [...profile.lists, { id, name, position, createdAt: now, items: [] }];
    return {};
}

function applyListRename(copy, change) {
    const listId = itemIdOf(change.listId);
    const name = trimName(change.name);
    if (!listId || !name) return { error: "bad request", field: "name" };
    const found = ensureProfile(copy, change.profileId, Date.now());
    if (found.error) return found;
    const list = found.profile.lists.find((l) => sameId(l.id, listId));
    if (!list) return { error: "gone" };
    list.name = name;
    return {};
}

function applyListDelete(copy, change) {
    const listId = itemIdOf(change.listId);
    if (!listId) return { error: "bad request" };
    const found = ensureProfile(copy, change.profileId, Date.now());
    if (found.error) return found;
    const before = found.profile.lists.length;
    found.profile.lists = found.profile.lists.filter((l) => !sameId(l.id, listId));
    if (found.profile.lists.length === before) return { error: "gone" };
    return {};
}

function applyListItem(copy, change, now) {
    const listId = itemIdOf(change.listId);
    const itemId = itemIdOf(change.itemId);
    if (!listId || !itemId || typeof change.on !== "boolean") return { error: "bad request" };
    const found = ensureProfile(copy, change.profileId, now);
    if (found.error) return found;
    const list = found.profile.lists.find((l) => sameId(l.id, listId));
    if (!list) return { error: "gone" };
    if (!Array.isArray(list.items)) list.items = [];
    const rest = list.items.filter((i) => !sameId(i.itemId, itemId));
    if (change.on) {
        const position = rest.reduce((m, i) => Math.max(m, i.position ?? 0), 0) + 1;
        list.items = [...rest, { itemId, addedAt: now, position }].sort((a, b) => byId(a.itemId, b.itemId));
    } else {
        list.items = rest;
    }
    return {};
}
