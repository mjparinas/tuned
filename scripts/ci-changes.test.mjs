import assert from "node:assert/strict";
import { test } from "node:test";
import { area, needs } from "./ci-changes.mjs";

test("Markdown and docs/ need nothing", () => {
    assert.deepEqual(needs(["AGENTS.md", "docs/decisions/player.md", "docs/branding/icon.svg", "devices/README.md"]), {
        mobile: false,
        web: false,
    });
});
test("the website alone skips the app", () => {
    assert.deepEqual(needs(["devices/lib/tv.mjs", "devices/public/index.html", "AGENTS.md"]), { mobile: false, web: true });
});
test("the app alone skips the website", () => {
    for (const f of [
        "app/src/main/kotlin/app/tuned/X.kt",
        "core/build.gradle.kts",
        "gradle/libs.versions.toml",
        "build.gradle.kts",
        "gradlew",
    ]) {
        assert.equal(area(f), "mobile", f);
    }
    assert.deepEqual(needs(["app/src/main/res/values/strings.xml", ".github/actions/setup-android/action.yml"]), {
        mobile: true,
        web: false,
    });
});
test("both, or anything shared, runs both", () => {
    assert.deepEqual(needs(["app/X.kt", "devices/api/backup.mjs"]), { mobile: true, web: true });
    for (const f of [".github/workflows/ci.yml", "scripts/next-version.mjs", "package.json", "prettier.config.mjs"]) {
        assert.deepEqual(needs([f]), { mobile: true, web: true }, f);
    }
});
test("blank lines are ignored", () => {
    assert.deepEqual(needs(["", "devices/lib/auth.mjs", ""]), { mobile: false, web: true });
});

test("the website's Markdown sources (posts and the privacy policy) run the website's tests", () => {
    assert.equal(area("devices/site/posts/guides/privacy.md"), "web");
    assert.equal(area("docs/privacy.md"), "web");
    assert.equal(area("docs/decisions/website.md"), "docs");
});
