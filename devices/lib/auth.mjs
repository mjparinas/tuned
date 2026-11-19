// Who is asking (docs/decisions/accounts.md): a signed-in browser (a session cookie, kept in Redis) or a signed-in
// device (a bearer token, kept hashed in Postgres). Also Google sign-in and the one-time link codes that turn a
// website sign-in into a device sign-in.
import { createHash, randomBytes } from "node:crypto";
import { command } from "./redis.mjs";
import { cookie, googleClaims, newToken, sameSite, sha, validChallenge } from "./accounts.mjs";
import { accountFor, deviceFor } from "./account-store.mjs";

export const SESSION_COOKIE = "tuned_session";
const SESSION_SECONDS = 30 * 86_400;
const STATE_SECONDS = 600;
/** A link code from a browser sign-in back to the app lasts two minutes; one for a TV lasts as long as its code. */
const LINK_SECONDS = 120;

export const json = (body, status = 200, headers = {}) =>
    Response.json(body, { status, headers: { "Cache-Control": "no-store", ...headers } });

export async function body(request) {
    try {
        return await request.json();
    } catch {
        return null;
    }
}

/** Any failure on our side answers 503, which the app takes as "try later", never as being signed out. */
export async function guarded(work) {
    try {
        return await work();
    } catch (error) {
        console.error(error);
        return json({ status: "unavailable" }, 503);
    }
}

/** True when `what` was done more than `max` times this hour by this visitor (kept only hashed, for the hour). */
export async function overLimit(request, what, max) {
    const ip = (request.headers.get("x-forwarded-for") ?? "").split(",")[0].trim();
    const key = `rl:${what}:${sha(ip)}`;
    const count = await command("INCR", key);
    if (count === 1) await command("EXPIRE", key, 3600);
    return count > max;
}

/** The account of a signed-in browser, or null. */
export async function webAccount(request) {
    const token = cookie(request.headers.get("cookie"), SESSION_COOKIE);
    if (!token) return null;
    return (await command("GET", `web:${sha(token)}`)) ?? null;
}

/** The signed-in device making the request, or null. */
export async function deviceCaller(request) {
    const header = request.headers.get("authorization") ?? "";
    const token = header.startsWith("Bearer ") ? header.slice(7) : "";
    return token.length >= 40 ? deviceFor(token) : null;
}

/**
 * The account behind a request: a device's token, or a browser's session for a request from our own pages (a
 * change from a browser must also pass the same-site check). `{ account, device }`, or null.
 */
export async function caller(request, { changing = false } = {}) {
    const device = await deviceCaller(request);
    if (device) return { account: device.account, device };
    if (changing && !sameSite(request)) return null;
    const account = await webAccount(request);
    return account ? { account, device: null } : null;
}

export async function newSession(account) {
    const token = newToken();
    await command("SET", `web:${sha(token)}`, account, "EX", SESSION_SECONDS);
    return `${SESSION_COOKIE}=${token}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${SESSION_SECONDS}`;
}

export async function endSession(request) {
    const token = cookie(request.headers.get("cookie"), SESSION_COOKIE);
    if (token) await command("DEL", `web:${sha(token)}`);
    return `${SESSION_COOKIE}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0`;
}

/** A one-time code that signs a device in to `account`; `challenge` is the app's PKCE challenge, if it sent one. */
export async function newLink(account, challenge = null, seconds = LINK_SECONDS) {
    const code = newToken();
    await command("SET", `link:${sha(code)}`, JSON.stringify({ account, challenge }), "EX", seconds);
    return code;
}

/** Uses up a link code: `{ account, challenge }`, or null. */
export async function takeLink(code) {
    if (typeof code !== "string" || code.length < 40) return null;
    const raw = await command("GETDEL", `link:${sha(code)}`);
    return raw ? JSON.parse(raw) : null;
}

const googleConfig = () => {
    const id = process.env.GOOGLE_WEB_CLIENT_ID;
    const secret = process.env.GOOGLE_WEB_CLIENT_SECRET;
    return id && secret ? { id, secret } : null;
};

export const signInReady = () => googleConfig() !== null && Boolean(process.env.DATABASE_URL);

/**
 * Where to send the browser to sign in with Google. `after` says what happens once signed in: `{ to: "web",
 * page }` opens a page of the site, `{ to: "app", challenge }` goes back to the app.
 */
export async function googleStart(origin, after) {
    const google = googleConfig();
    const state = newToken();
    const nonce = newToken();
    const verifier = randomBytes(32).toString("base64url");
    const challenge = createHash("sha256").update(verifier).digest("base64url");
    await command("SET", `oauth:${sha(state)}`, JSON.stringify({ verifier, nonce, after }), "EX", STATE_SECONDS);
    const query = new URLSearchParams({
        client_id: google.id,
        redirect_uri: `${origin}/api/auth/callback`,
        response_type: "code",
        scope: "openid email profile",
        state,
        nonce,
        code_challenge: challenge,
        code_challenge_method: "S256",
        prompt: "select_account",
    });
    return `https://accounts.google.com/o/oauth2/v2/auth?${query}`;
}

/** Google sending the browser back: the account (made the first time) and what was asked for after, or null. */
export async function googleFinish(origin, state, code) {
    const google = googleConfig();
    if (typeof state !== "string" || typeof code !== "string") return null;
    const raw = await command("GETDEL", `oauth:${sha(state)}`);
    if (!raw) return null;
    const { verifier, nonce, after } = JSON.parse(raw);
    const response = await fetch("https://oauth2.googleapis.com/token", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
            code,
            client_id: google.id,
            client_secret: google.secret,
            redirect_uri: `${origin}/api/auth/callback`,
            grant_type: "authorization_code",
            code_verifier: verifier,
        }),
    });
    if (!response.ok) return null;
    const claims = googleClaims((await response.json()).id_token, google.id, nonce, Math.floor(Date.now() / 1000));
    if (!claims) return null;
    return { account: await accountFor(claims), after };
}

export { validChallenge };
