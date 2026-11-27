import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
    appleClaims,
    appleClientSecret,
    appleUserName,
    baseVersion,
    checkoutAllowed,
    cookie,
    googleClaims,
    holdsSeat,
    parseDevice,
    pkceMatches,
    sameSite,
    seatPlan,
    stateCookie,
    stateMatches,
} from "./accounts.mjs";
import { generateKeyPairSync } from "node:crypto";

const now = 1_800_000_000_000;
const DAY = 86_400_000;
const e = (product, extra = {}) => ({ product, quantity: 1, status: "active", expiresAt: null, ...extra });

test("no Premium means no seats", () => {
    assert.deepEqual(seatPlan([], now), { premium: null, seatKind: null, extra: 0, limit: 0, canBuy: 0 });
    assert.equal(seatPlan([e("seat_once", { quantity: 4 })], now).limit, 0);
});

test("Premium comes with three seats, and extra seats add to them up to ten", () => {
    assert.equal(seatPlan([e("premium_lifetime")], now).limit, 3);
    assert.equal(seatPlan([e("premium_lifetime"), e("seat_once", { quantity: 2 })], now).limit, 5);
    const full = seatPlan([e("premium_lifetime"), e("seat_once", { quantity: 9 })], now);
    assert.equal(full.limit, 10);
    assert.equal(full.canBuy, 0);
});

test("lifetime Premium counts only one-time seats, and yearly Premium only yearly seats", () => {
    const lifetime = seatPlan([e("premium_lifetime"), e("seat_yearly", { quantity: 3, expiresAt: now + DAY })], now);
    assert.equal(lifetime.limit, 3);
    assert.equal(lifetime.seatKind, "seat_once");
    const yearly = seatPlan([e("premium_yearly", { expiresAt: now + DAY }), e("seat_once", { quantity: 3 })], now);
    assert.equal(yearly.limit, 3);
    assert.equal(yearly.seatKind, "seat_yearly");
});

test("lapsed or refunded purchases don't count", () => {
    assert.equal(seatPlan([e("premium_yearly", { expiresAt: now - 1 })], now).premium, null);
    assert.equal(seatPlan([e("premium_lifetime", { status: "refunded" })], now).premium, null);
});

test("checkout refuses mixing seat kinds, seats before Premium, a second Premium and going past ten", () => {
    const lifetime = seatPlan([e("premium_lifetime")], now);
    assert.equal(checkoutAllowed(lifetime, "seat_once", 2), "ok");
    assert.equal(checkoutAllowed(lifetime, "seat_yearly", 1), "wrong seat kind");
    assert.equal(checkoutAllowed(lifetime, "seat_once", 8), "too many seats");
    assert.equal(checkoutAllowed(lifetime, "premium_yearly", 1), "already premium");
    const none = seatPlan([], now);
    assert.equal(checkoutAllowed(none, "seat_once", 1), "premium first");
    assert.equal(checkoutAllowed(none, "premium_yearly", 1), "ok");
    assert.equal(checkoutAllowed(none, "premium_yearly", 2), "bad request");
    assert.equal(checkoutAllowed(none, "something", 1), "bad request");
});

test("a seat lapses after 60 days without use", () => {
    assert.equal(holdsSeat({ seated: true, lastSeen: now - 59 * DAY }, now), true);
    assert.equal(holdsSeat({ seated: true, lastSeen: now - 60 * DAY }, now), false);
    assert.equal(holdsSeat({ seated: false, lastSeen: now }, now), false);
});

test("PKCE checks the verifier against its S256 challenge", () => {
    const verifier = "a".repeat(43) + "-._~";
    const challenge = createHash("sha256").update(verifier).digest("base64url");
    assert.equal(pkceMatches(verifier, challenge), true);
    assert.equal(pkceMatches("b".repeat(43), challenge), false);
    assert.equal(pkceMatches("short", challenge), false);
});

const token = (claims) => `x.${Buffer.from(JSON.stringify(claims)).toString("base64url")}.y`;
const good = {
    iss: "https://accounts.google.com",
    aud: "client",
    exp: 2000,
    nonce: "n",
    email_verified: true,
    sub: "123",
    email: "a@b.c",
    name: "A",
};

test("a Google ID token is taken only for our client, unexpired, with our nonce and a verified email", () => {
    assert.deepEqual(googleClaims(token(good), "client", "n", 1000), { subject: "123", email: "a@b.c", name: "A" });
    assert.equal(googleClaims(token({ ...good, aud: "other" }), "client", "n", 1000), null);
    assert.equal(googleClaims(token(good), "client", "n", 3000), null);
    assert.equal(googleClaims(token({ ...good, nonce: "m" }), "client", "n", 1000), null);
    assert.equal(googleClaims(token({ ...good, email_verified: false }), "client", "n", 1000), null);
    assert.equal(googleClaims(token({ ...good, iss: "evil" }), "client", "n", 1000), null);
    assert.equal(googleClaims("nonsense", "client", "n", 1000), null);
});

const appleGood = {
    iss: "https://appleid.apple.com",
    aud: "com.tuned.web",
    exp: 2000,
    nonce: "n",
    email_verified: "true",
    sub: "001234.abcd",
    email: "a@privaterelay.appleid.com",
};

test("an Apple ID token needs Apple's issuer, our client, our nonce and a verified email", () => {
    assert.deepEqual(appleClaims(token(appleGood), "com.tuned.web", "n", 1000), {
        subject: "001234.abcd",
        email: "a@privaterelay.appleid.com",
        name: "",
    });
    assert.deepEqual(appleClaims(token({ ...appleGood, email_verified: true }), "com.tuned.web", "n", 1000), {
        subject: "001234.abcd",
        email: "a@privaterelay.appleid.com",
        name: "",
    });
    assert.equal(appleClaims(token({ ...appleGood, aud: "other" }), "com.tuned.web", "n", 1000), null);
    assert.equal(appleClaims(token(appleGood), "com.tuned.web", "n", 3000), null);
    assert.equal(appleClaims(token({ ...appleGood, nonce: "m" }), "com.tuned.web", "n", 1000), null);
    assert.equal(appleClaims(token({ ...appleGood, email_verified: false }), "com.tuned.web", "n", 1000), null);
    assert.equal(appleClaims(token({ ...appleGood, iss: "https://evil.example" }), "com.tuned.web", "n", 1000), null);
    assert.equal(appleClaims("nonsense", "com.tuned.web", "n", 1000), null);
});

test("Apple's user form field carries the name only on the first sign-in", () => {
    assert.equal(appleUserName(JSON.stringify({ name: { firstName: "Jane", lastName: "Doe" } })), "Jane Doe");
    assert.equal(appleUserName(JSON.stringify({ name: { firstName: "Jane" } })), "Jane");
    assert.equal(appleUserName(""), "");
    assert.equal(appleUserName("not-json"), "");
    assert.equal(appleUserName(null), "");
});

test("Apple's client secret is an ES256 JWT for our Services ID", () => {
    const { privateKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
    const pem = privateKey.export({ type: "pkcs8", format: "pem" });
    const secret = appleClientSecret({
        teamId: "TEAM123456",
        clientId: "com.tuned.web",
        keyId: "KEY1234567",
        privateKey: pem,
        nowSeconds: 1_000,
    });
    assert.ok(secret);
    const [header, payload] = secret.split(".");
    assert.deepEqual(JSON.parse(Buffer.from(header, "base64url").toString()), { alg: "ES256", kid: "KEY1234567" });
    assert.deepEqual(JSON.parse(Buffer.from(payload, "base64url").toString()), {
        iss: "TEAM123456",
        iat: 1_000,
        exp: 1_000 + 86_400,
        aud: "https://appleid.apple.com",
        sub: "com.tuned.web",
    });
    assert.equal(appleClientSecret({ teamId: "", clientId: "x", keyId: "y", privateKey: pem }), null);
});

test("a device needs a hashed id; the rest has safe defaults", () => {
    assert.equal(parseDevice({ device: "nope" }), null);
    assert.deepEqual(parseDevice({ device: "a".repeat(64), kind: "tv", build: "github", name: " Living room " }), {
        device: "a".repeat(64),
        kind: "tv",
        build: "github",
        name: "Living room",
    });
    assert.equal(parseDevice({ device: "a".repeat(64), kind: "fridge" }).kind, "phone");
    assert.equal(parseDevice({ device: "a".repeat(64) }).build, "play");
    // The Fire TV build (Amazon Appstore) is a store build of its own; anything unknown is still a store build.
    assert.equal(parseDevice({ device: "a".repeat(64), build: "amazon" }).build, "amazon");
    // The TV apps outside Android (docs/decisions/vega.md, the Samsung app in tv/tizen/).
    assert.equal(parseDevice({ device: "a".repeat(32), build: "vega", kind: "tv" }).build, "vega");
    assert.equal(parseDevice({ device: "a".repeat(32), build: "tizen", kind: "tv" }).build, "tizen");
    assert.equal(parseDevice({ device: "a".repeat(64), build: "sideways" }).build, "play");
});

test("cookies, same-site checks and backup versions", () => {
    assert.equal(cookie("a=1; tuned_session=abc=; b=2", "tuned_session"), "abc=");
    assert.equal(cookie("", "tuned_session"), null);
    const ours = new Request("https://tuned.example/api/x", {
        method: "POST",
        headers: { origin: "https://tuned.example", "content-type": "application/json" },
    });
    const theirs = new Request("https://tuned.example/api/x", {
        method: "POST",
        headers: { origin: "https://evil.example", "content-type": "application/json" },
    });
    const form = new Request("https://tuned.example/api/x", {
        method: "POST",
        headers: { origin: "https://tuned.example", "content-type": "text/plain" },
    });
    assert.equal(sameSite(ours), true);
    assert.equal(sameSite(theirs), false);
    assert.equal(sameSite(form), false);
    assert.equal(baseVersion('"4"'), 4);
    assert.equal(baseVersion("0"), 0);
    assert.equal(baseVersion('W/"4"'), 4);
    assert.equal(baseVersion("x"), null);
    assert.equal(baseVersion(null), null);
});

test("a Google sign-in finishes only in the browser that started it", () => {
    const state = "s".repeat(43);
    assert.match(stateCookie(state, 600), /^tuned_oauth=s{43}; Path=\/api\/auth\/callback; HttpOnly; Secure; SameSite=Lax; Max-Age=600$/);
    assert.equal(stateMatches(`a=1; tuned_oauth=${state}`, state), true);
    assert.equal(stateMatches(`tuned_oauth=${"t".repeat(43)}`, state), false);
    assert.equal(stateMatches("", state), false);
    assert.equal(stateMatches(null, state), false);
    assert.equal(stateMatches(`tuned_oauth=${state}`, null), false);
});
