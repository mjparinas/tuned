import { test } from "node:test";
import assert from "node:assert/strict";
import { bucket, checkValue, FLAGS, flagsFor } from "./flags.mjs";

const id = (n) => n.toString(16).padStart(32, "0");

test("defaults until an admin changes one", () => {
    const flags = flagsFor([], id(1));
    assert.equal(Object.keys(flags).length, FLAGS.length);
    assert.equal(flags.tmdb, true);
    assert.equal(flagsFor([{ key: "tmdb", value: false, share: 100 }], id(1)).tmdb, false);
});

test("a share rolls out to that many installs, and raising it only adds installs", () => {
    const installs = Array.from({ length: 2000 }, (_, i) => id(i));
    const at = (share) => new Set(installs.filter((i) => flagsFor([{ key: "cast", value: false, share }], i).cast === false));
    const ten = at(10);
    const fifty = at(50);
    assert.ok(ten.size > 150 && ten.size < 250, `${ten.size}`);
    for (const i of ten) assert.ok(fifty.has(i));
    assert.equal(bucket("cast", id(7)), bucket("cast", id(7)));
});

test("values of the wrong type are ignored", () => {
    assert.equal(checkValue("tmdb", "no"), undefined);
    assert.equal(checkValue("usage_batch_minutes", 5), undefined);
    assert.equal(checkValue("usage_batch_minutes", 30), 30);
    assert.equal(flagsFor([{ key: "tmdb", value: "no" }], id(1)).tmdb, true);
    assert.equal(flagsFor([{ key: "nope", value: true }], id(1)).nope, undefined);
});

test("the web player starts off until an admin turns it on", () => {
    assert.equal(flagsFor([], null).web_player, false);
    assert.equal(flagsFor([{ key: "web_player", value: true, share: 100 }], null).web_player, true);
});
