// What the app and the website may send for the admin's numbers (docs/decisions/admin.md). This allow-list is the
// only way an event or a property reaches the database: anything else is dropped. Nothing watched, typed or signed in
// with is ever on it: no channel, film, show, playlist, server address, login, search or IP address.
import { createHash } from "node:crypto";

const text = (max) => (v) => (typeof v === "string" && v.length > 0 ? v.slice(0, max) : undefined);
const oneOf =
    (...allowed) =>
    (v) =>
        allowed.includes(v) ? v : undefined;
const int = (min, max) => (v) => (Number.isInteger(v) && v >= min && v <= max ? v : undefined);
const bool = (v) => (typeof v === "boolean" ? v : undefined);

const KIND = oneOf("phone", "tablet", "tv");
const PLAYLIST = oneOf("xtream", "link", "file", "portal");
const SECTION = oneOf("live", "movies", "shows", "downloads", "events", "search", "guide");

/** About the device, sent with every batch and kept on its install row. */
export const INSTALL_FIELDS = {
    build: oneOf("github", "play"),
    version: text(24),
    versionCode: int(0, 2_000_000_000),
    android: text(12),
    sdk: int(1, 99),
    maker: text(40),
    model: text(60),
    kind: KIND,
    language: text(16),
    timeZone: text(40),
    source: text(40),
    utmSource: text(80),
    utmMedium: text(80),
    utmCampaign: text(80),
};

/** Events and the properties each may carry. */
export const EVENTS = {
    app_open: { cold: bool },
    playlist_added: { kind: PLAYLIST, viaPhone: bool },
    playlist_refreshed: { ok: bool, seconds: int(0, 86_400) },
    first_play: { section: SECTION },
    play: { section: SECTION, cast: bool, downloaded: bool },
    play_failed: { section: SECTION, reason: oneOf("network", "server", "codec", "timeout", "other") },
    screen: { name: oneOf("live", "movies", "shows", "guide", "events", "downloads", "search", "settings", "premium", "profiles") },
    feature: {
        name: oneOf(
            "auto_tune",
            "channel_scan",
            "download",
            "cast",
            "pip",
            "subtitle_search",
            "trakt",
            "catch_up",
            "profiles",
            "own_list",
            "widget",
            "tv_sign_in",
        ),
    },
    premium_seen: { from: text(40) },
    premium_bought: { product: oneOf("premium_yearly", "premium_lifetime", "seat_once", "seat_yearly"), trial: bool },
    premium_restored: {},
    seat_full: {},
    account_signed_in: { via: oneOf("phone", "tv_qr", "website") },
    backup_saved: { seconds: int(0, 3_600) },
    backup_restored: {},
    crash: { kind: oneOf("crash", "freeze") },
    update_installed: { from: text(24) },
    signed_out: { keptDownloads: bool },
};

export const MAX_EVENTS = 100;
const ID = /^[0-9a-f]{32}$/;

function pick(fields, raw) {
    const out = {};
    if (!raw || typeof raw !== "object") return out;
    for (const [key, check] of Object.entries(fields)) {
        const value = check(raw[key]);
        if (value !== undefined) out[key] = value;
    }
    return out;
}

/**
 * Checks a batch from the app: `{ install, device: {...}, events: [{ name, at, props }] }`. Returns null when the
 * install id is wrong; unknown events and properties are left out. Times more than a day ahead or 30 days behind are
 * set to now.
 */
export function cleanBatch(body, now = Date.now()) {
    if (!body || typeof body !== "object" || !ID.test(body.install ?? "")) return null;
    const events = [];
    for (const raw of Array.isArray(body.events) ? body.events.slice(0, MAX_EVENTS) : []) {
        const fields = EVENTS[raw?.name];
        if (!fields) continue;
        const at = Number.isFinite(raw.at) && raw.at > now - 30 * 86_400_000 && raw.at < now + 86_400_000 ? raw.at : now;
        events.push({ name: raw.name, at, props: pick(fields, raw.props) });
    }
    return { install: body.install, device: pick(INSTALL_FIELDS, body.device), test: body.test === true, events };
}

/** Problem reports sent from Settings › Report a problem: the person's words and the scrubbed log, length-capped. */
export function cleanReport(body) {
    if (!body || typeof body !== "object") return null;
    const what = text(4_000)(body.what?.trim());
    if (!what && !body.crash) return null;
    return {
        install: ID.test(body.install ?? "") ? body.install : null,
        device: pick(INSTALL_FIELDS, body.device),
        what: what ?? "",
        crash: text(20_000)(body.crash) ?? "",
        log: text(60_000)(body.log) ?? "",
        contact: text(200)(body.contact?.trim()) ?? "",
    };
}

/** Countries come from Vercel's geo header, never from the address. */
export const countryOf = (request) => {
    const c = request.headers.get("x-vercel-ip-country") ?? "";
    return /^[A-Z]{2}$/.test(c) ? c : null;
};

// --- The website's counter (cookieless, as Liftsmith's) ---

const PATHS = /^\/[a-z0-9/_-]{0,80}$/i;

/** A visitor counted once a day: a hash of the address, the browser and the day, with a secret salt; never stored raw. */
export function visitorHash(ip, userAgent, day, salt) {
    return createHash("sha256").update(`${salt}|${day}|${ip}|${userAgent}`).digest("hex").slice(0, 32);
}

/** Browser, system and form factor from the user agent, nothing finer. */
export function describeAgent(ua = "") {
    const browser = /Edg\//.test(ua)
        ? "Edge"
        : /SamsungBrowser/.test(ua)
          ? "Samsung Internet"
          : /Firefox\//.test(ua)
            ? "Firefox"
            : /Chrome\//.test(ua)
              ? "Chrome"
              : /Safari\//.test(ua)
                ? "Safari"
                : "Other";
    const os = /Android/.test(ua)
        ? "Android"
        : /iPhone|iPad|iPod/.test(ua)
          ? "iOS"
          : /Windows/.test(ua)
            ? "Windows"
            : /Mac OS X/.test(ua)
              ? "macOS"
              : /CrOS/.test(ua)
                ? "ChromeOS"
                : /Linux/.test(ua)
                  ? "Linux"
                  : "Other";
    const device = /\bTV\b|SmartTV|AFT|BRAVIA|GoogleTV/i.test(ua)
        ? "tv"
        : /Mobi|iPhone|Android(?!.*Tablet)/.test(ua)
          ? "phone"
          : /iPad|Tablet/.test(ua)
            ? "tablet"
            : "desktop";
    return { browser, os, device };
}

const BOT = /bot|crawl|spider|slurp|preview|headless|lighthouse|monitor/i;

/** A page view or a click from `/beacon.js`, or null for bots, odd paths and Global Privacy Control. */
export function cleanPageView(body, request) {
    const ua = request.headers.get("user-agent") ?? "";
    if (BOT.test(ua) || request.headers.get("sec-gpc") === "1") return null;
    if (!body || typeof body !== "object" || !PATHS.test(body.path ?? "")) return null;
    let referrer = null;
    try {
        const host = new URL(body.referrer).hostname.replace(/^www\./, "");
        const own = new URL(request.url).hostname.replace(/^www\./, "");
        referrer = host && host !== own ? host.slice(0, 80) : null;
    } catch {
        referrer = null;
    }
    return {
        path: body.path,
        kind: body.kind === "click" ? "click" : "view",
        target: body.kind === "click" ? (text(60)(body.target) ?? null) : null,
        referrer,
        utmSource: text(80)(body.utmSource) ?? null,
        utmMedium: text(80)(body.utmMedium) ?? null,
        utmCampaign: text(80)(body.utmCampaign) ?? null,
        ...describeAgent(ua),
    };
}
