import { test } from "node:test";
import assert from "node:assert/strict";
import { adminSessions, alive, IDLE_MS, MAX_MS, readCookie, sameOrigin } from "./session.mjs";

function fakeRedis() {
    const store = new Map();
    return {
        store,
        async command(op, k, v) {
            if (op === "SET") store.set(k, v);
            if (op === "GET") return store.get(k) ?? null;
            if (op === "DEL") store.delete(k);
            return "OK";
        },
    };
}

test("a session is kept hashed, read back from its cookie, and ended", async () => {
    const redis = fakeRedis();
    const sessions = adminSessions(redis);
    const { token, cookie } = await sessions.create("melvin@example.com", "admin", 1000);
    assert.match(cookie, /HttpOnly; Secure; SameSite=Strict/);
    assert.ok(![...redis.store.keys()].some((k) => k.includes(token)));
    const req = new Request("https://x.app/api/admin/session", { headers: { cookie: `a=b; tuned_admin=${token}` } });
    const read = await sessions.read(req, 2000);
    assert.equal(read.session.email, "melvin@example.com");
    assert.equal(read.session.verified, false);
    await sessions.end(req);
    assert.equal(await sessions.read(req, 3000), null);
});

test("sessions lapse after 30 idle days or 90 days in all", () => {
    assert.equal(alive({ started: 0, used: 0 }, IDLE_MS - 1), true);
    assert.equal(alive({ started: 0, used: 0 }, IDLE_MS), false);
    assert.equal(alive({ started: 0, used: MAX_MS - 10 }, MAX_MS), false);
    assert.equal(readCookie("x=1; tuned_admin=abc=", "tuned_admin"), "abc=");
});

test("a change must come from our own page", () => {
    const own = new Request("https://x.app/api/admin/flags", { method: "POST", headers: { origin: "https://x.app" } });
    const other = new Request("https://x.app/api/admin/flags", { method: "POST", headers: { origin: "https://evil.app" } });
    assert.equal(sameOrigin(own), true);
    assert.equal(sameOrigin(other), false);
    assert.equal(sameOrigin(new Request("https://x.app/api/admin/flags", { method: "POST" })), false);
});
