// The logo relay for the website's Channels and guide page (docs/decisions/web.md › Logos). An https page can't show
// an http picture, and many providers keep channel logos on http, so the page asks for those here. Pictures only:
// never a stream, a playlist, a guide or a login. Guarded against being turned on our own network (private
// addresses refused at every redirect), on anything but a picture (SVG refused, it can carry script), and on size.
// Plain functions, so they are tested without a server.
import { lookup } from "node:dns/promises";
import { isIP } from "node:net";

/** The most read: a logo is a few KB, so this is far more than any needs. */
export const MAX_LOGO_BYTES = 1_000_000;
export const TIMEOUT_MS = 6000;
const MAX_REDIRECTS = 3;
const TYPES = new Set([
    "image/png",
    "image/jpeg",
    "image/jpg",
    "image/gif",
    "image/webp",
    "image/avif",
    "image/x-icon",
    "image/vnd.microsoft.icon",
    "image/bmp",
]);

/** Whether an IP address is on a private, loopback, link-local or otherwise non-public network. */
export function isPrivateAddress(address) {
    const a = String(address).toLowerCase();
    const v4 = a.startsWith("::ffff:") && isIP(a.slice(7)) === 4 ? a.slice(7) : a;
    if (isIP(v4) === 4) {
        const [p, q] = v4.split(".").map(Number);
        return (
            p === 0 ||
            p === 10 ||
            p === 127 ||
            (p === 100 && q >= 64 && q <= 127) ||
            (p === 169 && q === 254) ||
            (p === 172 && q >= 16 && q <= 31) ||
            (p === 192 && q === 168) ||
            (p === 192 && q === 0) ||
            (p === 198 && (q === 18 || q === 19)) ||
            p >= 224
        );
    }
    if (isIP(a) === 6) return a === "::" || a === "::1" || /^f[cd]/.test(a) || /^fe[89ab]/.test(a) || a.startsWith("ff");
    return true;
}

/** The logo's address as a URL when it's one we'll fetch (http or https, a host, no login in it), else null. */
export function logoUrl(text) {
    let url;
    try {
        url = new URL(String(text ?? ""));
    } catch {
        return null;
    }
    if (url.protocol !== "http:" && url.protocol !== "https:") return null;
    if (url.username || url.password || !url.hostname) return null;
    return url;
}

/** Whether a host is safe to fetch: every address it has is public. */
export async function publicHost(hostname, resolve = lookup) {
    const host = hostname.replace(/^\[|\]$/g, "");
    if (isIP(host)) return !isPrivateAddress(host);
    if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".local")) return false;
    try {
        const all = await resolve(host, { all: true });
        return all.length > 0 && all.every((r) => !isPrivateAddress(r.address));
    } catch {
        return false;
    }
}

/** The picture's type when it's one we pass on, else null. */
export function imageType(header) {
    const type = String(header ?? "")
        .split(";")[0]
        .trim()
        .toLowerCase();
    return TYPES.has(type) ? type : null;
}

/**
 * Fetches a logo: `{ bytes, type }`, or `{ error }` ("bad request", "refused", "not an image", "too large",
 * "unreachable"). Each redirect is checked again, so one can't lead onto a private address.
 */
export async function fetchLogo(text, { fetcher = fetch, resolve = lookup, timeout = TIMEOUT_MS } = {}) {
    let url = logoUrl(text);
    if (!url) return { error: "bad request" };
    const signal = AbortSignal.timeout(timeout);
    for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
        if (!(await publicHost(url.hostname, resolve))) return { error: "refused" };
        let res;
        try {
            res = await fetcher(url, { redirect: "manual", signal, headers: { accept: "image/*" } });
        } catch {
            return { error: "unreachable" };
        }
        if (res.status >= 300 && res.status < 400 && res.headers.get("location")) {
            url = logoUrl(new URL(res.headers.get("location"), url).href);
            if (!url) return { error: "bad request" };
            continue;
        }
        if (!res.ok) return { error: "unreachable" };
        const type = imageType(res.headers.get("content-type"));
        if (!type) return { error: "not an image" };
        if (Number(res.headers.get("content-length") ?? 0) > MAX_LOGO_BYTES) return { error: "too large" };
        try {
            const bytes = await readCapped(res.body, MAX_LOGO_BYTES);
            return bytes ? { bytes, type } : { error: "too large" };
        } catch {
            return { error: "unreachable" };
        }
    }
    return { error: "unreachable" };
}

/** The whole body, or null once it passes `max` bytes. */
async function readCapped(body, max) {
    if (!body) return Buffer.alloc(0);
    const parts = [];
    let size = 0;
    for await (const chunk of body) {
        size += chunk.length;
        if (size > max) return null;
        parts.push(Buffer.from(chunk));
    }
    return Buffer.concat(parts);
}
