// Checks a purchase with the Google Play Developer API, as a service account with "View financial data" in Play
// Console. The key is the Vercel environment variable GOOGLE_PLAY_SERVICE_ACCOUNT (the JSON file's contents).
import { createSign } from "node:crypto";

export const PACKAGE = "app.tuned";
export const YEARLY = "premium_yearly";
export const LIFETIME = "premium_lifetime";
export const PRODUCTS = [YEARLY, LIFETIME];
/** Extra seats for an account (docs/decisions/accounts.md): one-time (bought in any quantity) or yearly. */
export const SEAT_ONCE = "seat_once";
export const SEAT_YEARLY = "seat_yearly";
const ONE_TIME = [LIFETIME, SEAT_ONCE];

let cached = { token: null, until: 0 };

function base64url(data) {
    return Buffer.from(data).toString("base64url");
}

async function accessToken() {
    if (cached.token && Date.now() < cached.until) return cached.token;
    const raw = process.env.GOOGLE_PLAY_SERVICE_ACCOUNT;
    if (!raw) throw new Error("GOOGLE_PLAY_SERVICE_ACCOUNT isn't set");
    const account = JSON.parse(raw);
    const now = Math.floor(Date.now() / 1000);
    const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const claims = base64url(
        JSON.stringify({
            iss: account.client_email,
            scope: "https://www.googleapis.com/auth/androidpublisher",
            aud: "https://oauth2.googleapis.com/token",
            iat: now,
            exp: now + 3600,
        }),
    );
    const signature = createSign("RSA-SHA256").update(`${header}.${claims}`).sign(account.private_key, "base64url");
    const response = await fetch("https://oauth2.googleapis.com/token", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
            grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
            assertion: `${header}.${claims}.${signature}`,
        }),
    });
    if (!response.ok) throw new Error(`Google sign-in answered ${response.status}`);
    const { access_token: token, expires_in: seconds } = await response.json();
    cached = { token, until: Date.now() + (seconds - 60) * 1000 };
    return token;
}

/** An order number as people type it from the receipt: upper case, without a renewal's "..N" ending. */
export function orderKey(order) {
    return String(order ?? "")
        .trim()
        .toUpperCase()
        .replace(/\.\.\d+$/, "");
}

/** What a Play answer means: whether Premium is still paid for, and the order number on the receipt. */
export function reading(product, body, now) {
    if (ONE_TIME.includes(product)) return { valid: body.purchaseState === 0, order: orderKey(body.orderId) };
    const items = body.lineItems ?? [];
    const valid = items.some((item) => Date.parse(item.expiryTime) > now);
    const order = items.map((item) => item.latestSuccessfulOrderId).find(Boolean) ?? body.latestOrderId;
    return { valid, order: orderKey(order) };
}

/** A Play answer as an account's entitlement: active or not, how many, and when a yearly purchase runs out. */
export function entitlementReading(product, body, now) {
    const { valid } = reading(product, body, now);
    if (ONE_TIME.includes(product)) {
        return { status: valid ? "active" : "refunded", quantity: Math.max(1, Number(body.quantity) || 1), expiresAt: null };
    }
    const ends = (body.lineItems ?? []).map((item) => Date.parse(item.expiryTime)).filter(Number.isFinite);
    return { status: valid ? "active" : "expired", quantity: 1, expiresAt: ends.length ? Math.max(...ends) : null };
}

async function ask(product, token) {
    const base = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}/purchases`;
    const url = ONE_TIME.includes(product)
        ? `${base}/products/${product}/tokens/${encodeURIComponent(token)}`
        : `${base}/subscriptionsv2/tokens/${encodeURIComponent(token)}`;
    const response = await fetch(url, { headers: { Authorization: `Bearer ${await accessToken()}` } });
    if (response.status === 400 || response.status === 404 || response.status === 410) return null;
    if (!response.ok) throw new Error(`Play answered ${response.status}`);
    return response.json();
}

/** Asks Play about a purchase for an account: an entitlement reading, or null for a token Play doesn't know. */
export async function checkEntitlement(product, token) {
    const body = await ask(product, token);
    return body ? entitlementReading(product, body, Date.now()) : null;
}

/**
 * Asks Play about a purchase token. Returns `{ valid, order }`, or throws when Play couldn't be asked, which the
 * caller answers with "try later" so nobody is locked out by an outage.
 */
export async function check(product, token) {
    const body = await ask(product, token);
    return body ? reading(product, body, Date.now()) : { valid: false, order: "" };
}
