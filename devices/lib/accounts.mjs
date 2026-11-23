// The rules of accounts that need no database or network, so they are tested on their own (accounts.test.mjs).
// See docs/decisions/accounts.md.
import { createHash, createPrivateKey, randomBytes, sign, timingSafeEqual } from "node:crypto";

/** Seats that come with Premium, and the most an account can have with extra seats. */
export const INCLUDED_SEATS = 3;
export const MAX_SEATS = 10;
/** A device not used for this long gives its seat back, as in premium.md. */
export const EXPIRY_DAYS = 60;

export const PREMIUM_YEARLY = "premium_yearly";
export const PREMIUM_LIFETIME = "premium_lifetime";
export const SEAT_ONCE = "seat_once";
export const SEAT_YEARLY = "seat_yearly";
export const PRODUCTS = [PREMIUM_YEARLY, PREMIUM_LIFETIME, SEAT_ONCE, SEAT_YEARLY];

/**
 * Extra seats match the kind of Premium (Melvin, 2026-11-19: "make it so you can't mix lifetime account and
 * yearly seats"): lifetime Premium takes one-time seats, yearly Premium takes yearly seats.
 */
export const SEAT_FOR = { lifetime: SEAT_ONCE, yearly: SEAT_YEARLY };

export const sha = (text) => createHash("sha256").update(text).digest("hex");
export const newToken = () => randomBytes(32).toString("base64url");

const live = (e, now) => e.status === "active" && (e.expiresAt == null || e.expiresAt > now);

/**
 * What an account's entitlements add up to at `now`: which Premium it has (lifetime wins over yearly), the kind of
 * extra seat it may buy, how many extra seats count, and how many devices can use Premium. Seats of the other kind
 * don't count, so the two can never be mixed.
 */
export function seatPlan(entitlements, now) {
    const active = entitlements.filter((e) => live(e, now));
    const premium = active.some((e) => e.product === PREMIUM_LIFETIME)
        ? "lifetime"
        : active.some((e) => e.product === PREMIUM_YEARLY)
          ? "yearly"
          : null;
    if (!premium) return { premium: null, seatKind: null, extra: 0, limit: 0, canBuy: 0 };
    const seatKind = SEAT_FOR[premium];
    const owned = active.filter((e) => e.product === seatKind).reduce((sum, e) => sum + e.quantity, 0);
    const extra = Math.min(owned, MAX_SEATS - INCLUDED_SEATS);
    return { premium, seatKind, extra, limit: INCLUDED_SEATS + extra, canBuy: MAX_SEATS - INCLUDED_SEATS - extra };
}

/**
 * Whether a checkout for `product` × `quantity` is allowed for an account whose plan is `plan`. Premium is bought
 * once (a second Premium of either kind is refused), and seats only of the matching kind, up to the cap.
 */
export function checkoutAllowed(plan, product, quantity) {
    if (!PRODUCTS.includes(product) || !Number.isInteger(quantity) || quantity < 1) return "bad request";
    if (product === PREMIUM_YEARLY || product === PREMIUM_LIFETIME) {
        if (quantity !== 1) return "bad request";
        return plan.premium ? "already premium" : "ok";
    }
    if (!plan.premium) return "premium first";
    if (product !== plan.seatKind) return "wrong seat kind";
    return quantity <= plan.canBuy ? "ok" : "too many seats";
}

/** True when a device last used at `lastSeen` still holds its seat. */
export const holdsSeat = (device, now) => device.seated && now - device.lastSeen < EXPIRY_DAYS * 86_400_000;

/** PKCE (RFC 7636, S256): the app's verifier must hash to the challenge it sent when sign-in began. */
export function pkceMatches(verifier, challenge) {
    if (typeof verifier !== "string" || !/^[A-Za-z0-9._~-]{43,128}$/.test(verifier) || typeof challenge !== "string") return false;
    const made = Buffer.from(createHash("sha256").update(verifier).digest("base64url"));
    const given = Buffer.from(challenge);
    return made.length === given.length && timingSafeEqual(made, given);
}

export const validChallenge = (challenge) => typeof challenge === "string" && /^[A-Za-z0-9_-]{43}$/.test(challenge);

/**
 * The claims of a Google ID token received straight from Google's token endpoint over TLS, which OpenID Connect
 * lets us trust without checking the signature; the issuer, audience, expiry and nonce are still checked.
 */
export function googleClaims(idToken, clientId, nonce, nowSeconds) {
    const parts = String(idToken ?? "").split(".");
    if (parts.length !== 3) return null;
    let claims;
    try {
        claims = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
    } catch {
        return null;
    }
    const issuer = claims.iss === "accounts.google.com" || claims.iss === "https://accounts.google.com";
    if (!issuer || claims.aud !== clientId || !(claims.exp > nowSeconds) || claims.nonce !== nonce) return null;
    if (claims.email_verified !== true || typeof claims.sub !== "string" || typeof claims.email !== "string") return null;
    return { subject: claims.sub, email: claims.email, name: typeof claims.name === "string" ? claims.name.slice(0, 100) : "" };
}

/**
 * The claims of an Apple ID token from Apple's token endpoint over TLS (same trust model as Google). Apple may send
 * `email_verified` as the string "true", and the person's name is not in the token: it arrives only once, in the
 * authorize form's `user` field, and is passed in separately.
 */
export function appleClaims(idToken, clientId, nonce, nowSeconds) {
    const parts = String(idToken ?? "").split(".");
    if (parts.length !== 3) return null;
    let claims;
    try {
        claims = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
    } catch {
        return null;
    }
    if (claims.iss !== "https://appleid.apple.com" || claims.aud !== clientId || !(claims.exp > nowSeconds)) return null;
    if (claims.nonce !== nonce || typeof claims.sub !== "string") return null;
    const verified = claims.email_verified === true || claims.email_verified === "true";
    if (!verified || typeof claims.email !== "string" || !claims.email) return null;
    return { subject: claims.sub, email: claims.email, name: "" };
}

/**
 * The display name Apple sends only on the first authorize (`user` form field as JSON). Empty when Apple omits it
 * (every later sign-in) or when the JSON is unreadable.
 */
export function appleUserName(userField) {
    if (typeof userField !== "string" || !userField) return "";
    try {
        const user = JSON.parse(userField);
        const first = typeof user?.name?.firstName === "string" ? user.name.firstName : "";
        const last = typeof user?.name?.lastName === "string" ? user.name.lastName : "";
        return `${first} ${last}`.trim().slice(0, 100);
    } catch {
        return "";
    }
}

/**
 * Apple's client secret is a short-lived ES256 JWT we mint from the Sign in with Apple key (Team ID, Key ID, .p8).
 * Max lifetime Apple allows is six months; we mint for a day so a rotated key is picked up quickly.
 */
export function appleClientSecret({ teamId, clientId, keyId, privateKey, nowSeconds = Math.floor(Date.now() / 1000) }) {
    const pem = String(privateKey ?? "").includes("\\n") ? String(privateKey).replace(/\\n/g, "\n") : String(privateKey ?? "");
    if (!teamId || !clientId || !keyId || !pem.includes("BEGIN")) return null;
    const header = Buffer.from(JSON.stringify({ alg: "ES256", kid: keyId })).toString("base64url");
    const payload = Buffer.from(
        JSON.stringify({
            iss: teamId,
            iat: nowSeconds,
            exp: nowSeconds + 86_400,
            aud: "https://appleid.apple.com",
            sub: clientId,
        }),
    ).toString("base64url");
    const data = `${header}.${payload}`;
    try {
        const key = createPrivateKey(pem);
        const sig = sign("SHA256", Buffer.from(data), { key, dsaEncoding: "ieee-p1363" });
        return `${data}.${sig.toString("base64url")}`;
    } catch {
        return null;
    }
}

/** A device signing in: its hashed Android id (as for Premium seats), a name, its kind and which build it is. */
export function parseDevice(body) {
    const device = body?.device;
    if (typeof device !== "string" || !/^[0-9a-f]{32,64}$/.test(device)) return null;
    const kind = ["phone", "tablet", "tv"].includes(body?.kind) ? body.kind : "phone";
    const build = body?.build === "github" ? "github" : "play";
    const name = typeof body?.name === "string" && body.name.trim() ? body.name.trim().slice(0, 60) : "Android device";
    return { device, kind, build, name };
}

/**
 * The cookie that ties a Google sign-in to the browser that started it (login CSRF): it holds the sign-in's state,
 * and Google's answer is taken only when the same state comes back in it. Sent to the callback path only.
 */
export const STATE_COOKIE = "tuned_oauth";
export const stateCookie = (state, seconds) =>
    `${STATE_COOKIE}=${state}; Path=/api/auth/callback; HttpOnly; Secure; SameSite=Lax; Max-Age=${seconds}`;

/** True when the browser's state cookie holds the `state` Google sent back. */
export function stateMatches(cookieHeader, state) {
    const kept = cookie(cookieHeader, STATE_COOKIE);
    if (typeof state !== "string" || !kept || kept.length !== state.length) return false;
    return timingSafeEqual(Buffer.from(kept), Buffer.from(state));
}

/** The value of one cookie in a Cookie header. */
export function cookie(header, name) {
    for (const part of String(header ?? "").split(";")) {
        const [key, ...rest] = part.trim().split("=");
        if (key === name) return rest.join("=");
    }
    return null;
}

/**
 * True when a request that changes something comes from our own pages: the browser's Origin must be ours, and the
 * body JSON, which a form on another site can't send without asking first.
 */
export function sameSite(request) {
    const origin = request.headers.get("origin");
    const type = request.headers.get("content-type") ?? "";
    return origin === new URL(request.url).origin && type.startsWith("application/json");
}

/** The version a backup upload starts from, from its If-Match header ("0" for the first). */
export function baseVersion(header) {
    const value = String(header ?? "")
        .trim()
        .replace(/^W\//, "")
        .replace(/"/g, "");
    return /^\d{1,9}$/.test(value) ? Number(value) : null;
}

/** How many old versions of a backup are kept, and the largest copy taken. */
export const BACKUP_VERSIONS = 5;
export const MAX_BACKUP_BYTES = 4 * 1024 * 1024;
