import { test } from "node:test";
import assert from "node:assert/strict";
import { biggestDrop, change, fillDays, funnel, monthlyCosts, mrr, periodOf, revenue } from "./metrics.mjs";

test("a funnel step is never more than the one before", () => {
    const steps = funnel([
        { label: "Installed", count: 100 },
        { label: "Added a playlist", count: 80 },
        { label: "Played something", count: 90 },
        { label: "Bought Premium", count: 4 },
    ]);
    assert.deepEqual(
        steps.map((s) => s.count),
        [100, 80, 80, 4],
    );
    assert.equal(steps[3].ofFirst, 4);
    assert.equal(steps[3].ofPrevious, 5);
    assert.deepEqual(biggestDrop(steps), { from: "Played something", to: "Bought Premium", lost: 95 });
    assert.equal(biggestDrop(funnel([{ label: "a", count: 3 }, { label: "b", count: 0 }])), null);
});

test("revenue is gross at list price, net of each store's fee, and refunds come off", () => {
    const r = revenue([
        { source: "play", product: "premium_lifetime", quantity: 1, status: "active" },
        { source: "web", product: "extra_seat", quantity: 2, status: "active" },
        { source: "web", product: "premium_yearly", quantity: 1, status: "refunded" },
        { source: "grant", product: "premium_lifetime", quantity: 1, status: "active" },
    ]);
    assert.equal(r.gross, 2499 + 998);
    assert.equal(r.net, 2499 - 375 + 998 - 59);
    assert.equal(r.refunds, 999);
    assert.equal(r.count, 2);
    assert.equal(mrr([{ source: "play", product: "premium_yearly", quantity: 1, status: "active" }]), 83);
    assert.equal(monthlyCosts([{ cents: 2000, every: "month" }, { cents: 1200, every: "year" }]), 2100);
});

test("periods, changes and empty days", () => {
    assert.equal(periodOf("7"), 7);
    assert.equal(periodOf("13"), 30);
    assert.equal(change(15, 10), 50);
    assert.equal(change(5, 0), null);
    const days = fillDays([{ day: "2026-11-19", count: 3 }], 3, Date.parse("2026-11-19T12:00:00Z"));
    assert.deepEqual(days, [
        { day: "2026-11-17", count: 0 },
        { day: "2026-11-18", count: 0 },
        { day: "2026-11-19", count: 3 },
    ]);
});
