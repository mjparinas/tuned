// The web player's one-browser lease (lib/watch-lease.mjs, docs/decisions/web.md).
import { test } from "node:test";
import assert from "node:assert/strict";
import { claimLease, holdsLease, validBrowserId } from "./watch-lease.mjs";

const A = "11111111-1111-4111-8111-111111111111";
const B = "22222222-2222-4222-8222-222222222222";

test("a browser id looks like a UUID", () => {
    assert.equal(validBrowserId(A), A);
    assert.equal(validBrowserId(A.toUpperCase()), A);
    assert.equal(validBrowserId("not-a-uuid"), null);
    assert.equal(validBrowserId(""), null);
    assert.equal(validBrowserId(null), null);
});

test("an empty lease is taken by the first browser", () => {
    assert.deepEqual(claimLease(null, A), { holder: A, tookOver: false });
    assert.deepEqual(claimLease(A, A), { holder: A, tookOver: false });
});

test("a second browser takes over by default", () => {
    assert.deepEqual(claimLease(A, B), { holder: B, tookOver: true });
});

test("a heartbeat without steal leaves the other browser alone", () => {
    assert.deepEqual(claimLease(A, B, { steal: false }), { holder: A, blocked: true });
});

test("holdsLease is true only for the holder", () => {
    assert.equal(holdsLease(A, A), true);
    assert.equal(holdsLease(A, B), false);
    assert.equal(holdsLease(null, A), false);
    assert.equal(holdsLease(A, "bad"), false);
});

test("a bad browser id is turned away", () => {
    assert.deepEqual(claimLease(null, "nope"), { error: "bad request" });
});
