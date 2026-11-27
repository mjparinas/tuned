import assert from "node:assert/strict";
import { test } from "node:test";
import { area, needs } from "./ci-changes.mjs";

test("Markdown and docs/ need nothing", () => {
    assert.deepEqual(needs(["AGENTS.md", "docs/decisions/player.md", "docs/branding/icon.svg", "devices/README.md"]), {
        mobile: false,
        web: false,
        vega: false,
        tizen: false,
    });
});
test("the website alone skips the app", () => {
    assert.deepEqual(needs(["devices/lib/tv.mjs", "devices/public/index.html", "AGENTS.md"]), {
        mobile: false,
        web: true,
        vega: false,
        tizen: false,
    });
});
test("the app alone skips the website", () => {
    for (const f of [
        "app/src/main/kotlin/app/tuned/X.kt",
        "core/build.gradle.kts",
        "data/src/main/kotlin/com/tunedplayer/app/data/Settings.kt",
        "gradle/libs.versions.toml",
        "build.gradle.kts",
        "gradlew",
    ]) {
        assert.equal(area(f), "mobile", f);
    }
    assert.deepEqual(needs(["app/src/main/res/values/strings.xml", ".github/actions/setup-android/action.yml"]), {
        mobile: true,
        web: false,
        vega: false,
        tizen: false,
    });
});
test("both, or anything shared, runs both", () => {
    assert.deepEqual(needs(["app/X.kt", "devices/api/backup.mjs"]), { mobile: true, web: true, vega: false, tizen: false });
    for (const f of [".github/workflows/ci.yml", "scripts/next-version.mjs", "package.json", "prettier.config.mjs"]) {
        assert.deepEqual(needs([f]), { mobile: true, web: true, vega: true, tizen: true }, f);
    }
});
test("blank lines are ignored", () => {
    assert.deepEqual(needs(["", "devices/lib/auth.mjs", ""]), { mobile: false, web: true, vega: false, tizen: false });
});

test("the website's Markdown sources (posts and the privacy policy) run the website's tests", () => {
    assert.equal(area("devices/site/posts/guides/privacy.md"), "web");
    assert.equal(area("docs/privacy.md"), "web");
    assert.equal(area("docs/decisions/website.md"), "docs");
});

test("each TV app runs only its own job, and tv/shared/ runs both", () => {
    assert.deepEqual(needs(["tv/vega/src/App.tsx"]), { mobile: false, web: false, vega: true, tizen: false });
    assert.deepEqual(needs(["tv/tizen/src/main.ts"]), { mobile: false, web: false, vega: false, tizen: true });
    assert.deepEqual(needs(["tv/shared/src/xtream.ts"]), { mobile: false, web: false, vega: true, tizen: true });
    assert.equal(area("tv/vega/README.md"), "docs");
});
