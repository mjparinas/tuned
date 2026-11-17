import assert from "node:assert/strict";
import { test } from "node:test";
import { bump, next } from "./next-version.mjs";

test("a fix bumps the patch", () => assert.equal(bump("0.1.0", ["fix: resume big downloads"]), "0.1.1"));
test("anything unlabelled bumps the patch", () => assert.equal(bump("0.1.3", ["Bump okhttp"]), "0.1.4"));
test("a feature bumps the minor", () => assert.equal(bump("0.1.3", ["fix: a", "feat(player): b"]), "0.2.0"));
test("a breaking change bumps the major", () => {
    assert.equal(bump("0.4.2", ["feat!: new data format"]), "1.0.0");
    assert.equal(bump("0.4.2", ["fix: x\n\nBREAKING CHANGE: sign in again"]), "1.0.0");
});
test("the first release uses package.json", () => assert.equal(next("0.1.0", undefined, []), "0.1.0"));
test("from the last release tag", () => assert.equal(next("0.1.0", "v0.1.0-build.1", ["feat: accent colours"]), "0.2.0"));
test("a hand bump in package.json wins", () => assert.equal(next("1.0.0", "v0.3.0-build.9", ["fix: x"]), "1.0.0"));
test("a merge commit counts its pull request title", () =>
    assert.equal(bump("0.2.0", ["Merge pull request #12 from mjparinas/claude/cast\n\nfeat: cast to Chromecast"]), "0.3.0"));
