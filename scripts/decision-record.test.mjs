import assert from "node:assert/strict";
import { test } from "node:test";
import { check, needsRecord, updatesRecord } from "./decision-record.mjs";

test("features need a record", () => {
    assert.ok(needsRecord("feat: cast to Chromecast"));
    assert.ok(needsRecord("feat(player): skip intro"));
    assert.ok(needsRecord("feat!: new data format"));
    assert.ok(needsRecord("fix!: sign in again"));
});
test("removing something needs a record", () => {
    assert.ok(needsRecord("fix: remove picture-in-picture"));
    assert.ok(needsRecord("refactor: drop the OMDb ratings"));
    assert.ok(needsRecord('Revert "feat: multiview"'));
});
test("fixes, chores and Dependabot don't", () => {
    assert.ok(!needsRecord("fix: tapping the cast button no longer crashes the player"));
    assert.ok(!needsRecord("Bump the minor-and-patch group across 1 directory with 2 updates"));
    assert.ok(!needsRecord("ci: cache Gradle between runs"));
    assert.ok(!needsRecord("perf: faster Live TV and Shows tabs"));
});
test("AGENTS.md or a decision file counts as the record", () => {
    assert.ok(updatesRecord(["app/src/main/X.kt", "AGENTS.md"]));
    assert.ok(updatesRecord(["docs/decisions/player.md"]));
    assert.ok(!updatesRecord(["app/src/main/X.kt", "README.md", "docs/decisions/notes.txt"]));
});
test("check passes or explains", () => {
    assert.equal(check("feat: x", ["docs/decisions/player.md"]), null);
    assert.equal(check("fix: y", ["app/src/main/X.kt"]), null);
    assert.match(check("feat: x", ["app/src/main/X.kt"]), /docs\/decisions/);
});
