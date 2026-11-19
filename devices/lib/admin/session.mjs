// The admin's own session (docs/decisions/admin.md), separate from the website's account session: a random token in
// an HttpOnly cookie, kept only hashed in Redis. It starts from a Google sign-in whose email is on ADMIN_EMAILS or
// SUPPORT_EMAILS, and opens the panel only after the second step (an authenticator code or a recovery code).
import { createHash, randomBytes } from "node:crypto";

export const ADMIN_COOKIE = "tuned_admin";
export const IDLE_MS = 30 * 86_400_000;
export const MAX_MS = 90 * 86_400_000;
const sha = (text) => createHash("sha256").update(text).digest("hex");
const key = (token) => `admin:${sha(token)}`;

export function readCookie(header, name) {
    for (const part of String(header ?? "").split(";")) {
        const [k, ...v] = part.trim().split("=");
        if (k === name) return v.join("=");
    }
    return null;
}

export const cookieHeader = (token, seconds) => `${ADMIN_COOKIE}=${token}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${seconds}`;

/** Still usable at `now`: used in the last 30 days and started in the last 90. */
export const alive = (s, now) => Boolean(s) && now - s.used < IDLE_MS && now - s.started < MAX_MS;

/** Sessions over `redis` (`command(...args)`). */
export function adminSessions(redis) {
    return {
        async create(email, role, now = Date.now()) {
            const token = randomBytes(32).toString("base64url");
            const session = { email, role, verified: false, started: now, used: now };
            await redis.command("SET", key(token), JSON.stringify(session), "PX", IDLE_MS);
            return { token, session, cookie: cookieHeader(token, IDLE_MS / 1000) };
        },
        /** The session for a request, renewed at most once an hour, or null. */
        async read(request, now = Date.now()) {
            const token = readCookie(request.headers.get("cookie"), ADMIN_COOKIE);
            if (!token) return null;
            const raw = await redis.command("GET", key(token));
            const session = raw ? JSON.parse(raw) : null;
            if (!alive(session, now)) return null;
            if (now - session.used > 3_600_000) {
                session.used = now;
                await redis.command("SET", key(token), JSON.stringify(session), "PX", Math.min(IDLE_MS, MAX_MS - (now - session.started)));
            }
            return { token, session };
        },
        async update(token, session) {
            await redis.command("SET", key(token), JSON.stringify(session), "KEEPTTL");
        },
        async end(request) {
            const token = readCookie(request.headers.get("cookie"), ADMIN_COOKIE);
            if (token) await redis.command("DEL", key(token));
            return cookieHeader("", 0);
        },
    };
}

/** True when a changing request came from our own page (its Origin is ours). */
export function sameOrigin(request) {
    const origin = request.headers.get("origin");
    return origin != null && origin === new URL(request.url).origin;
}
