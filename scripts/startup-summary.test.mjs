import { test } from "node:test";
import assert from "node:assert/strict";
import { summarize } from "./startup-summary.mjs";

const report = (name, median) => ({ benchmarks: [{ name, metrics: { timeToInitialDisplayMs: { median } } }] });

test("compares the medians without and with the profile", () => {
    const out = summarize([report("startupWithoutProfile", 1000), report("startupWithProfile", 700)]);
    assert.match(out, /\| startupWithoutProfile \| 1000 ms \|/);
    assert.match(out, /by 30% \(1000 ms to 700 ms\)/);
});

test("says so when nothing was measured", () => {
    assert.equal(summarize([{ benchmarks: [] }]), "No startup numbers were recorded.\n");
});
