import { test } from "node:test";
import assert from "node:assert/strict";
import { LIFETIME, orderKey, reading, YEARLY } from "./play.mjs";

const now = Date.parse("2026-11-19T00:00:00Z");

test("a yearly plan counts while any line item is unexpired, and gives its first order number", () => {
    const body = { lineItems: [{ expiryTime: "2027-11-19T00:00:00Z", latestSuccessfulOrderId: "GPA.1234-5678-9012-34567..2" }] };
    assert.deepEqual(reading(YEARLY, body, now), { valid: true, order: "GPA.1234-5678-9012-34567" });
    assert.equal(reading(YEARLY, { lineItems: [{ expiryTime: "2026-01-01T00:00:00Z" }] }, now).valid, false);
});

test("a lifetime purchase counts while Play says it is purchased", () => {
    assert.deepEqual(reading(LIFETIME, { purchaseState: 0, orderId: "gpa.1111-2222-3333-44444" }, now), {
        valid: true,
        order: "GPA.1111-2222-3333-44444",
    });
    assert.equal(reading(LIFETIME, { purchaseState: 1 }, now).valid, false);
});

test("order numbers match however they're typed", () => {
    assert.equal(orderKey("  gpa.1234-5678-9012-34567..0 "), "GPA.1234-5678-9012-34567");
});
