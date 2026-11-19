// The provider check's rules (docs/decisions/web.md), kept free of the page so they run under `node --test`.
// Everything here runs in the person's browser: the address and login never reach Tuned's server.

/** Reads what the person typed: "host", "host:port", a full address, or an Xtream get.php / player_api.php link. */
export function parseServer(input) {
    let text = String(input ?? "").trim();
    if (!text) return null;
    if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(text)) text = `http://${text}`;
    let url;
    try {
        url = new URL(text);
    } catch {
        return null;
    }
    if (url.protocol !== "http:" && url.protocol !== "https:") return null;
    const username = url.searchParams.get("username");
    const password = url.searchParams.get("password");
    const path = url.pathname.replace(/\/+$/, "");
    const xtreamLink = /\/(get|player_api)\.php$/i.test(path) && username != null && password != null;
    const playlistLink = !xtreamLink && /\.(m3u8?|php)$/i.test(path) ? url.href : null;
    let base = `${url.origin}${path}`;
    if (xtreamLink) base = `${url.origin}${path.replace(/\/(get|player_api)\.php$/i, "")}`;
    else if (playlistLink) base = url.origin;
    return {
        protocol: url.protocol.slice(0, -1),
        host: url.hostname,
        port: url.port,
        origin: url.origin,
        base,
        username: xtreamLink ? username : null,
        password: xtreamLink ? password : null,
        playlistLink,
    };
}

/** True for a MAC (Stalker) portal address, which a browser can never use (it must set Cookie and User-Agent). */
export function looksLikePortal(input) {
    return /\/(stalker_portal|c)\/?$|\/portal\.php|\/server\/load\.php/i.test(String(input ?? "").trim());
}

const enc = encodeURIComponent;

export function apiUrl(base, username, password, action, params = {}) {
    const query = [`username=${enc(username)}`, `password=${enc(password)}`];
    if (action) query.push(`action=${enc(action)}`);
    for (const [k, v] of Object.entries(params)) query.push(`${enc(k)}=${enc(v)}`);
    return `${base}/player_api.php?${query.join("&")}`;
}

export function liveUrl(base, username, password, id, ext) {
    return `${base}/live/${enc(username)}/${enc(password)}/${enc(id)}.${ext}`;
}

export function movieUrl(base, username, password, id, ext) {
    return `${base}/movie/${enc(username)}/${enc(password)}/${enc(id)}.${ext || "mp4"}`;
}

/**
 * The https twin of an http address: Xtream's server_info names its https port when it has one; otherwise the same
 * host on 443. Null when the address is already https.
 */
export function httpsVariant(server, serverInfo) {
    if (server.protocol === "https") return null;
    const port = String(serverInfo?.https_port ?? "").trim();
    const suffix = port && port !== "443" && port !== "0" ? `:${port}` : "";
    const path = server.base.slice(server.origin.length);
    return `https://${server.host}${suffix}${path}`;
}

/** The first `limit` streams of an M3U playlist. */
export function parseM3u(text, limit = 200) {
    const out = [];
    let pending = null;
    for (const raw of String(text).split(/\r?\n/)) {
        const line = raw.trim();
        if (!line) continue;
        if (line.startsWith("#EXTINF")) {
            const comma = line.lastIndexOf(",");
            pending = {
                name: comma >= 0 ? line.slice(comma + 1).trim() : "",
                group: /group-title="([^"]*)"/i.exec(line)?.[1] ?? "",
            };
        } else if (!line.startsWith("#")) {
            out.push({ name: pending?.name || line, group: pending?.group ?? "", url: line });
            pending = null;
            if (out.length >= limit) break;
        }
    }
    return out;
}

/** What plays a link: hls.js (or Safari) for .m3u8, mpegts.js for .ts and bare live links, the browser for files. */
export function streamKind(url) {
    const path = String(url).split(/[?#]/)[0].toLowerCase();
    if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return "hls";
    if (/\.(mp4|m4v|mkv|webm|mov|avi)$/.test(path)) return "file";
    return "ts";
}

/** True when an http link sits on an https page, which browsers block unless the person allows insecure content. */
export function isMixed(url, pageProtocol) {
    return pageProtocol === "https:" && /^http:/i.test(String(url));
}

/**
 * Why a fetch failed. Browsers give the same error for every failure, so the page tries twice: a normal request
 * (needs CORS) and an opaque one (doesn't). If the opaque one gets through, the server is there and only CORS is
 * missing.
 */
export function access({ cors, opaque, mixed, insecureAllowed }) {
    if (cors) return "ok";
    if (mixed && !insecureAllowed) return "insecure";
    if (opaque) return "cors";
    return "unreachable";
}

const AUDIO = {
    aac: "mp4a.40.2",
    mp3: "mp4a.40.34",
    mp2: "mp4a.69",
    ac3: "ac-3",
    eac3: "ec-3",
    opus: "opus",
    flac: "flac",
    dts: "dtsc",
    truehd: "mlpa",
};
const VIDEO = {
    h264: "avc1.640028",
    avc: "avc1.640028",
    hevc: "hvc1.1.6.L120.90",
    h265: "hvc1.1.6.L120.90",
    vp9: "vp09.00.40.08",
    av1: "av01.0.08M.08",
    mpeg2video: "mp2v",
};
const CONTAINER = {
    mp4: "video/mp4",
    m4v: "video/mp4",
    mov: "video/mp4",
    mkv: "video/x-matroska",
    webm: "video/webm",
    ts: "video/mp2t",
    avi: "video/x-msvideo",
};

/** A MIME codec string for an ffprobe-style codec name (what Xtream's get_vod_info reports), or null. */
export function codecMime(name) {
    const key = String(name ?? "")
        .toLowerCase()
        .replace(/[^a-z0-9]/g, "");
    return VIDEO[key] ?? AUDIO[key] ?? null;
}

export function containerMime(ext) {
    return CONTAINER[String(ext ?? "").toLowerCase()] ?? null;
}

/** mpegts.js and hls.js describe codecs as MIME strings already ("avc1.64001f", "mp4a.40.2"); give them a name. */
export function codecName(mime) {
    const m = String(mime ?? "").toLowerCase();
    if (m.startsWith("avc")) return "H.264";
    if (m.startsWith("hvc") || m.startsWith("hev")) return "HEVC";
    if (m.startsWith("mp4a.40.34") || m === "mp3") return "MP3";
    if (m.startsWith("mp4a.69") || m.startsWith("mp4a.6b") || m === "mp2") return "MP2";
    if (m.startsWith("mp4a")) return "AAC";
    if (m.startsWith("ac-3")) return "AC-3 (Dolby)";
    if (m.startsWith("ec-3")) return "E-AC-3 (Dolby)";
    if (m.startsWith("opus")) return "Opus";
    if (m.startsWith("dts")) return "DTS";
    return mime || "unknown";
}

/** Takes the person's login out of any text before it is shown (the stream and API links carry it). */
export function redact(text, ...secrets) {
    let out = String(text ?? "");
    for (const secret of secrets) {
        if (!secret) continue;
        for (const form of new Set([secret, encodeURIComponent(secret)])) out = out.split(form).join("•••");
    }
    return out;
}

/**
 * The one answer the check gives (the plan's five, plus the two cases where nothing could be tested).
 * facts: { portal, login: "ok" | "wrong" | null, api: access(), live: "ok" | "codec" | access() | null,
 *          film: "ok" | "codec" | "sound" | access() | null, needsInsecure, safari }
 */
export function verdict(facts) {
    if (facts.portal) return "portal";
    const results = [facts.api, facts.live].filter(Boolean);
    if (facts.login === "wrong") return "login";
    if (results.includes("cors")) return "blocked";
    if (results.includes("insecure")) return facts.safari ? "not-safari" : "allow-insecure";
    if (results.includes("unreachable")) return "unreachable";
    if (facts.live === "codec") return "codec";
    if (facts.api === "ok" && facts.needsInsecure) return facts.safari ? "not-safari" : "works-after-setting";
    if (facts.api === "ok" && (facts.live === "ok" || facts.live == null)) return facts.live === "ok" ? "works" : "list-only";
    return "unreachable";
}
