// The shared filters, sorting and Continue Watching (docs/decisions/vega.md, tizen.md).
import assert from "node:assert/strict";
import { test } from "node:test";
import { is247, isAdult, visible } from "./filters.ts";
import { MemoryStore } from "./platform.ts";
import { progressKey, WatchProgress } from "./progress.ts";
import { nextSort, sortItems } from "./sort.ts";

test("adult and 24/7 follow Android's rules", () => {
    assert.equal(isAdult("XXX | Adults", "Anything"), true);
    assert.equal(isAdult("Kids", "Adult Swim"), false);
    assert.equal(isAdult("US | 18+", "Late"), true);
    assert.equal(isAdult("Entertainment", "Young Adults Hour"), false);
    for (const name of ["24/7 Friends", "US| 24/7 CARTOONS", "24-7 Seinfeld", "247: The Simpsons", "Friends 24x7", "24 / 7 Mash"]) {
        assert.equal(is247("", name), true, name);
    }
    assert.equal(is247("News", "Channel 1247"), false);
    assert.equal(is247("News", "24 Hours"), false);

    const items = [
        { name: "News", group: "UK" },
        { name: "Late", group: "XXX" },
        { name: "24/7 Friends", group: "Comedy" },
        { name: "Seinfeld", group: "24/7 Shows" },
    ];
    const names = (hideAdult: boolean, hide247: boolean) =>
        visible(
            items,
            { hideAdult, hide247 },
            (x) => x.name,
            (x) => x.group,
        ).map((x) => x.name);
    assert.deepEqual(names(false, false), ["News", "Late", "24/7 Friends", "Seinfeld"]);
    assert.deepEqual(names(true, false), ["News", "24/7 Friends", "Seinfeld"]);
    assert.deepEqual(names(true, true), ["News"]);
});

test("sorting keeps items without a value last, in playlist order", () => {
    const items = [
        { name: "The Zebra", year: 2001, rating: 6, added: 3 },
        { name: "apple", year: null, rating: 8, added: 1 },
        { name: "Mango", year: 2020, rating: null, added: null },
        { name: "Banana", year: 2001, rating: 9, added: 2 },
    ];
    const names = (xs: typeof items) => xs.map((x) => x.name);
    assert.deepEqual(names(sortItems(items, "provider")), ["The Zebra", "apple", "Mango", "Banana"]);
    assert.deepEqual(names(sortItems(items, "year")), ["Mango", "The Zebra", "Banana", "apple"]);
    assert.deepEqual(names(sortItems(items, "rating")), ["Banana", "apple", "The Zebra", "Mango"]);
    assert.deepEqual(names(sortItems(items, "added")), ["The Zebra", "Banana", "apple", "Mango"]);
    assert.deepEqual(names(sortItems(items, "title")), ["apple", "Banana", "Mango", "The Zebra"]);
    assert.deepEqual(names(sortItems(items, "rating", (x) => (x.name === "Mango" ? 10 : null))), ["Mango", "Banana", "apple", "The Zebra"]);
    assert.equal(nextSort("title"), "provider");
    assert.equal(nextSort("provider"), "added");
});

test("Continue Watching has the newest unfinished one per title, and finished ones leave it", async () => {
    const store = new MemoryStore();
    let clock = 1000;
    const p = new WatchProgress(store, progressKey("current"), () => clock++);
    await p.load();
    await p.save("e1", "s1", 600, 1500);
    await p.save("m1", "m1", 300, 6000);
    await p.save("e2", "s1", 120, 1500);
    await p.save("m2", "m2", 3, 6000);
    assert.equal(p.position("m2"), 0);
    assert.deepEqual(
        p.continueWatching().map((w) => w.id),
        ["e2", "m1"],
    );
    await p.save("m1", "m1", 5900, 6000);
    assert.equal(p.position("m1"), 0);
    assert.equal(p.position("e1"), 600);

    const again = new WatchProgress(store, progressKey("current"));
    await again.load();
    assert.deepEqual(
        again.continueWatching().map((w) => w.id),
        ["e2"],
    );

    const fresh = new WatchProgress(new MemoryStore(), "progress.x", () => 50);
    await fresh.load();
    await fresh.adopt({ m9: 400, e7: 2, m8: 90 }, (id) => (id.startsWith("m") ? id : null));
    assert.deepEqual(
        fresh.continueWatching().map((w) => w.id),
        ["m8", "m9"],
    );
});
