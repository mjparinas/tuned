import { test } from "node:test";
import assert from "node:assert/strict";
import { base32, codeAt, fromBase32, newRecoveryCodes, STEP_SECONDS, useRecovery, hashRecovery, verify } from "./totp.mjs";

// RFC 6238's SHA-1 test key, "12345678901234567890".
const SECRET = base32(Buffer.from("12345678901234567890"));

test("codes match RFC 6238's test vectors", () => {
    assert.equal(codeAt(SECRET, Math.floor(59 / 30)), "287082");
    assert.equal(codeAt(SECRET, Math.floor(1111111109 / 30)), "081804");
    assert.equal(codeAt(SECRET, Math.floor(1234567890 / 30)), "005924");
});

test("base32 goes there and back", () => {
    assert.deepEqual(fromBase32(SECRET), Buffer.from("12345678901234567890"));
});

test("a code works one step either side, and never twice", () => {
    const now = 1234567890 * 1000;
    const step = Math.floor(1234567890 / STEP_SECONDS);
    assert.equal(verify(SECRET, codeAt(SECRET, step), now), step);
    assert.equal(verify(SECRET, codeAt(SECRET, step - 1), now), step - 1);
    assert.equal(verify(SECRET, codeAt(SECRET, step + 1), now), step + 1);
    assert.equal(verify(SECRET, codeAt(SECRET, step - 2), now), null);
    assert.equal(verify(SECRET, codeAt(SECRET, step), now, step), null);
    assert.equal(verify(SECRET, "12345", now), null);
});

test("recovery codes are single use and forgiving about case and dashes", () => {
    const codes = newRecoveryCodes();
    assert.equal(codes.length, 8);
    assert.match(codes[0], /^[2-9A-Z]{4}-[2-9A-Z]{4}$/);
    const hashes = codes.map(hashRecovery);
    const left = useRecovery(hashes, codes[3].toLowerCase().replace("-", ""));
    assert.equal(left.length, 7);
    assert.equal(useRecovery(left, codes[3]), null);
});
