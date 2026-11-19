import { test } from "node:test";
import assert from "node:assert/strict";
import { freshness, roleOf, SETTINGS, settingsState } from "./system.mjs";
import { derive, snoozeUntil } from "./todo.mjs";

test("a pair is only set when whole, and the Marketplace's other names count", () => {
    const state = settingsState({ STRIPE_SECRET_KEY: "x", UPSTASH_REDIS_REST_URL: "u", UPSTASH_REDIS_REST_TOKEN: "t" });
    const by = Object.fromEntries(state.map((s) => [s.names[0], s.state]));
    assert.equal(by.STRIPE_SECRET_KEY, "partly set");
    assert.equal(by.KV_REST_API_URL, "set");
    assert.equal(by.DATABASE_URL, "missing");
    assert.equal(state.length, SETTINGS.length);
    assert.ok(!JSON.stringify(state).includes('"x"'));
});

test("roles come from the two lists, admins first", () => {
    const env = { ADMIN_EMAILS: "Melvin@Example.com, b@x.com", SUPPORT_EMAILS: "help@x.com,b@x.com" };
    assert.equal(roleOf("melvin@example.com", env), "admin");
    assert.equal(roleOf("b@x.com", env), "admin");
    assert.equal(roleOf("help@x.com", env), "support");
    assert.equal(roleOf("someone@x.com", env), null);
    assert.equal(roleOf("", env), null);
});

test("to-dos come back when their data changes, and after a snooze", () => {
    const now = Date.parse("2026-11-19T20:00:00Z");
    const facts = { openReports: 2, oldestReportDays: 3, missingSettings: [{ names: ["DATABASE_URL"], powers: "Neon" }] };
    const all = derive(facts, {}, now);
    assert.deepEqual(
        all.map((i) => i.key),
        ["reports:2", "setup:DATABASE_URL"],
    );
    assert.equal(derive(facts, { "reports:2": null }, now).length, 1);
    assert.equal(derive({ ...facts, openReports: 3 }, { "reports:2": null }, now).length, 2);
    const until = snoozeUntil("tomorrow", now);
    assert.equal(new Date(until).toISOString(), "2026-11-20T08:00:00.000Z");
    assert.equal(derive(facts, { "reports:2": until }, now).length, 1);
    assert.equal(derive(facts, { "reports:2": until }, until).length, 2);
    assert.equal(freshness(30, 26, 50), "amber");
    assert.equal(freshness(null, 1, 2), "red");
});
