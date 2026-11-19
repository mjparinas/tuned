import { test } from "node:test";
import assert from "node:assert/strict";
import { ALERT_GAP_MS, alertsToSend } from "./automations.mjs";

const now = Date.parse("2026-11-19T14:05:00Z");
const quiet = { purchases: 0, refunds: 0, reports: 0 };
const digest = { installs: 4, accounts: 1, purchases: 1, reports: 0, visitors: 30 };

test("an alert goes once per 6 hours, only when switched on", () => {
    const happened = { ...quiet, purchases: 2, reports: 1 };
    const kinds = (x) => alertsToSend({ happened, failures: 0, digest: null, switches: {}, last: {}, now, ...x }).map((a) => a.kind);
    assert.deepEqual(kinds({}), ["purchase", "report"]);
    assert.deepEqual(kinds({ switches: { report: false } }), ["purchase"]);
    assert.deepEqual(kinds({ last: { purchase: now - ALERT_GAP_MS + 1 } }), ["report"]);
    assert.equal(alertsToSend({ happened, failures: 0, switches: {}, last: {}, now })[0].subject, "Tuned: 2 new purchases");
});

test("the digest goes once a day after 13:00 UTC", () => {
    const base = { happened: quiet, failures: 0, digest, switches: {}, last: {} };
    assert.equal(alertsToSend({ ...base, now: Date.parse("2026-11-19T12:59:00Z") }).length, 0);
    const sent = alertsToSend({ ...base, now });
    assert.equal(sent[0].kind, "digest");
    assert.match(sent[0].text, /Installs: 4/);
    assert.equal(alertsToSend({ ...base, last: { digest: now - 3_600_000 }, now }).length, 0);
});

test("five failed sign-ins raise one alert", () => {
    assert.equal(alertsToSend({ happened: quiet, failures: 4, switches: {}, last: {}, now }).length, 0);
    assert.equal(alertsToSend({ happened: quiet, failures: 5, switches: {}, last: {}, now })[0].kind, "signin_failures");
});
