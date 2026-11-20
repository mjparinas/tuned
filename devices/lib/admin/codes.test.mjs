import { test } from "node:test";
import assert from "node:assert/strict";
import { checkNewCode, newCode, normalizeCode, redeemProblem } from "./codes.mjs";

test("codes are tidied, and odd ones refused", () => {
    assert.equal(normalizeCode(" friends 2026 "), "FRIENDS-2026");
    assert.equal(normalizeCode("ab"), null);
    assert.equal(normalizeCode("-NOPE-"), null);
    assert.match(newCode(), /^TUNED-[2-9A-Z]{4}-[2-9A-Z]{4}$/);
});

test("the admin's form is checked", () => {
    assert.equal(checkNewCode({ kind: "gold" }).error, "Pick what the code gives.");
    assert.equal(checkNewCode({ kind: "seats", quantity: 11 }).error, "Seats are 1 to 10.");
    const ok = checkNewCode({ kind: "premium", code: "launch", maxUses: 3, days: 30 });
    assert.deepEqual(ok, { code: "LAUNCH", kind: "premium", quantity: 1, maxUses: 3, days: 30, note: "" });
});

test("a code stops at its limit, its date, and one use per account", () => {
    const now = Date.parse("2026-11-19T00:00:00Z");
    const row = { uses: 0, max_uses: 2, expires_at: "2026-12-01T00:00:00Z", disabled: false };
    assert.equal(redeemProblem(row, false, now), null);
    assert.equal(redeemProblem(row, true, now), "already used");
    assert.equal(redeemProblem({ ...row, uses: 2 }, false, now), "used up");
    assert.equal(redeemProblem(row, false, Date.parse("2026-12-02T00:00:00Z")), "expired");
    assert.equal(redeemProblem({ ...row, disabled: true }, false, now), "not found");
    assert.equal(redeemProblem(null, false, now), "not found");
});

test("redeeming casts the account for both tables, text for the redemption and uuid for the grant", async () => {
    // Without the casts Postgres types $2 as text and the grant into entitlements (uuid) fails, so no code redeems.
    const { redeemSql } = await import("./data.mjs");
    for (const yearly of [false, true]) {
        const sql = redeemSql(yearly);
        assert.match(sql, /INSERT INTO code_redemptions \(code, account_id\) SELECT code, \$2::text /);
        assert.match(sql, /SELECT 'grant', \$5, \$2::uuid, /);
    }
    assert.match(redeemSql(true), /interval '1 year'/);
    assert.match(redeemSql(false), /'active', NULL FROM/);
});
