import { test } from "node:test";
import assert from "node:assert/strict";
import { adminApi } from "./api.mjs";
import { adminSessions } from "./session.mjs";
import { codeAt, stepOf } from "./totp.mjs";

const ORIGIN = "https://tuned.example";
const now = 1_800_000_000_000;

function world() {
    const store = new Map();
    const redis = {
        async command(op, k, v) {
            if (op === "SET") store.set(k, v);
            else if (op === "GET") return store.get(k) ?? null;
            else if (op === "DEL") store.delete(k);
            else if (op === "INCR") {
                store.set(k, Number(store.get(k) ?? 0) + 1);
                return store.get(k);
            }
            return "OK";
        },
    };
    const twoFactor = new Map();
    const audits = [];
    const flags = [];
    const data = {
        audit: async (actor, action, detail) => audits.push({ actor, action, detail }),
        twoFactor: async (email) => twoFactor.get(email) ?? null,
        saveTwoFactor: async (email, secret) => twoFactor.set(email, { secret, enabled_at: null, last_step: -1, recovery: [] }),
        enableTwoFactor: async (email, step, recovery) =>
            Object.assign(twoFactor.get(email), { enabled_at: "now", last_step: step, recovery }),
        useTwoFactorStep: async (email, step) => {
            const tf = twoFactor.get(email);
            if (tf.last_step >= step) return [];
            tf.last_step = step;
            return [{ email }];
        },
        setRecovery: async (email, recovery) => Object.assign(twoFactor.get(email), { recovery }),
        flagRows: async () => flags,
        setFlag: async (key, value, share) => flags.push({ key, value, share }),
        codes: async () => [],
    };
    const accounts = { a1: { email: "Melvin@Example.com" }, a2: { email: "help@example.com" }, a3: { email: "rando@example.com" } };
    const env = { ADMIN_EMAILS: "melvin@example.com", SUPPORT_EMAILS: "help@example.com" };
    const handle = adminApi({
        data,
        redis,
        env,
        sessions: adminSessions(redis),
        auth: {
            webAccount: async (r) => /web=(\w+)/.exec(r.headers.get("cookie") ?? "")?.[1] ?? null,
            account: async (id) => accounts[id] ?? null,
            googleStart: async () => "https://accounts.google.com/x",
            signInReady: () => true,
        },
        now: () => now,
    });
    let cookie = "";
    const call = async (action, { method = "GET", body, web, origin = ORIGIN } = {}) => {
        const headers = { cookie: [cookie, web ? `web=${web}` : ""].filter(Boolean).join("; ") };
        if (method === "POST" && origin) headers.origin = origin;
        const response = await handle(
            new Request(`${ORIGIN}/api/admin/${action}`, { method, headers, body: body ? JSON.stringify(body) : undefined }),
            action,
        );
        const set = response.headers.get("set-cookie");
        if (set) cookie = set.split(";")[0];
        return { status: response.status, body: await response.json() };
    };
    return { call, audits, twoFactor, reset: () => (cookie = "") };
}

test("nobody gets in without a Google sign-in on the list", async () => {
    const w = world();
    assert.equal((await w.call("session")).body.signedIn, false);
    assert.equal((await w.call("flags")).status, 401);
    const refused = await w.call("session", { web: "a3" });
    assert.equal(refused.status, 403);
    assert.equal(refused.body.refused, "rando@example.com");
});

test("an admin sets up the second step, and only then sees the panel", async () => {
    const w = world();
    const first = await w.call("session", { web: "a1" });
    assert.deepEqual([first.body.role, first.body.twoFactor], ["admin", "setup"]);
    assert.equal((await w.call("flags")).status, 401);
    const setup = await w.call("two-factor", { method: "POST", body: { action: "setup" } });
    assert.match(setup.body.link, /^otpauth:\/\/totp\//);
    assert.equal((await w.call("two-factor", { method: "POST", body: { action: "enable", code: "000000" } })).status, 403);
    const on = await w.call("two-factor", { method: "POST", body: { action: "enable", code: codeAt(setup.body.secret, stepOf(now)) } });
    assert.equal(on.body.recovery.length, 8);
    assert.equal((await w.call("flags")).status, 200);
    assert.ok(w.audits.some((a) => a.action === "2fa.enabled"));

    // A new browser needs the code again, and the same code can't be used twice.
    w.reset();
    assert.equal((await w.call("session", { web: "a1" })).body.twoFactor, "code");
    const again = await w.call("two-factor", { method: "POST", body: { action: "verify", code: codeAt(setup.body.secret, stepOf(now)) } });
    assert.equal(again.status, 403);
    const recovery = await w.call("two-factor", { method: "POST", body: { action: "verify", recovery: on.body.recovery[0] } });
    assert.equal(recovery.status, 200);
    assert.equal(w.twoFactor.get("melvin@example.com").recovery.length, 7);
});

test("changes need an admin, our own page and a valid value, and are audited", async () => {
    const w = world();
    await w.call("session", { web: "a1" });
    const setup = await w.call("two-factor", { method: "POST", body: { action: "setup" } });
    await w.call("two-factor", { method: "POST", body: { action: "enable", code: codeAt(setup.body.secret, stepOf(now)) } });
    assert.equal(
        (await w.call("flags", { method: "POST", body: { key: "tmdb", value: false }, origin: "https://evil.example" })).status,
        400,
    );
    assert.equal((await w.call("flags", { method: "POST", body: { key: "tmdb", value: "off" } })).status, 400);
    assert.equal((await w.call("flags", { method: "POST", body: { key: "tmdb", value: false, share: 25 } })).status, 200);
    assert.deepEqual(w.audits.at(-1), {
        actor: "melvin@example.com",
        action: "flag.set",
        detail: { key: "tmdb", value: false, share: 25 },
    });
});

test("support staff can read but not change", async () => {
    const w = world();
    await w.call("session", { web: "a2" });
    const setup = await w.call("two-factor", { method: "POST", body: { action: "setup" } });
    await w.call("two-factor", { method: "POST", body: { action: "enable", code: codeAt(setup.body.secret, stepOf(now)) } });
    assert.equal((await w.call("codes")).status, 200);
    const change = await w.call("flags", { method: "POST", body: { key: "tmdb", value: false } });
    assert.equal(change.status, 403);
    assert.equal(change.body.status, "read only");
});

test("wrong codes are limited to five every 15 minutes", async () => {
    const w = world();
    await w.call("session", { web: "a1" });
    await w.call("two-factor", { method: "POST", body: { action: "setup" } });
    const statuses = [];
    for (let i = 0; i < 6; i++)
        statuses.push((await w.call("two-factor", { method: "POST", body: { action: "enable", code: "000000" } })).status);
    assert.deepEqual(statuses, [403, 403, 403, 403, 403, 429]);
});
