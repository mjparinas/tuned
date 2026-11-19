import assert from "node:assert/strict";
import { test } from "node:test";
import { recordedPurchases } from "./account-api.mjs";
import { sha } from "./accounts.mjs";

test("only purchases the account holds as active Play entitlements count as recorded", () => {
    const purchases = [
        { product: "seat_once", token: "token-recorded-0001" },
        { product: "seat_once", token: "token-refunded-0002" },
        { product: "seat_once", token: "token-unknown-00003" },
    ];
    const entitlements = [
        { source: "play", ref: sha("token-recorded-0001"), status: "active" },
        { source: "play", ref: sha("token-refunded-0002"), status: "refunded" },
        { source: "stripe", ref: sha("token-unknown-00003"), status: "active" },
    ];
    assert.deepEqual(recordedPurchases(purchases, entitlements), [sha("token-recorded-0001")]);
});
