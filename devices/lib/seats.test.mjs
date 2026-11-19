import { test } from "node:test";
import assert from "node:assert/strict";
import { activate, deactivate, EXPIRY_DAYS, fresh, LIMIT, list, parse, removeFromSite } from "./seats.mjs";

const DAY = 24 * 60 * 60 * 1000;
const now = 1_800_000_000_000;
const id = (n) => String(n).repeat(32).slice(0, 32);
const three = {
    [id(1)]: { name: "Phone", lastSeen: now },
    [id(2)]: { name: "TV", lastSeen: now - DAY },
    [id(3)]: { name: "Tablet", lastSeen: now - 2 * DAY },
};

test("three devices get seats and a fourth hears full", () => {
    assert.equal(LIMIT, 3);
    assert.equal(activate({}, id(1), "Phone", now).status, "active");
    const fourth = activate(three, id(4), "Laptop", now);
    assert.equal(fourth.status, "full");
    assert.equal(Object.keys(fourth.seats).length, 3);
});

test("a device that already has a seat keeps it, which is how a reinstall reuses its spot", () => {
    const again = activate(three, id(2), "TV", now);
    assert.equal(again.status, "active");
    assert.equal(again.seats[id(2)].lastSeen, now);
});

test("a device unused for 60 days frees its seat", () => {
    const stale = { ...three, [id(3)]: { name: "Lost phone", lastSeen: now - EXPIRY_DAYS * DAY } };
    assert.equal(Object.keys(fresh(stale, now)).length, 2);
    assert.equal(activate(stale, id(4), "New phone", now).status, "active");
});

test("an activated device can free another's seat, and then the fourth device gets in", () => {
    const freed = deactivate(three, id(1), id(3), now);
    assert.equal(freed.allowed, true);
    assert.equal(freed.status, "active");
    assert.equal(activate(freed.seats, id(4), "Laptop", now).status, "active");
});

test("a device can give up its own seat", () => {
    const gone = deactivate(three, id(2), id(2), now);
    assert.equal(gone.allowed, true);
    assert.equal(gone.status, "full");
    assert.equal(gone.seats[id(2)], undefined);
});

test("a locked-out device can't free someone else's seat", () => {
    const pushed = deactivate(three, id(4), id(1), now);
    assert.equal(pushed.allowed, false);
    assert.equal(Object.keys(pushed.seats).length, 3);
});

test("the list is most recently used first", () => {
    assert.deepEqual(
        list(three).map((d) => d.name),
        ["Phone", "TV", "Tablet"],
    );
});

test("bad requests are refused", () => {
    const products = ["premium_yearly"];
    const good = { product: "premium_yearly", token: "x".repeat(40), device: id(1), name: " Pixel 8 " };
    assert.equal(parse(good, products).name, "Pixel 8");
    assert.equal(parse({ ...good, product: "other" }, products), null);
    assert.equal(parse({ ...good, device: "nothex" }, products), null);
    assert.equal(parse({ ...good, target: "bad" }, products), null);
    assert.equal(parse(null, products), null);
    assert.equal(parse({ ...good, name: "" }, products).name, "Android device");
});

test("the website removes one device or all of them", () => {
    assert.deepEqual(removeFromSite(three, "all", now), {});
    assert.deepEqual(Object.keys(removeFromSite(three, id(2), now)), [id(1), id(3)]);
});
