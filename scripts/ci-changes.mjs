// Which parts of CI a change needs (Melvin, 2026-11-19: "if there are only changes to the web app, only build the
// webapp and skip mobile builds and vice versa", and "dont run ci if there are only markdown file changes").
// ci.yml pipes the changed files in, one per line, and gates its jobs on what this prints.

/**
 * What a changed file belongs to: "docs" (needs nothing), "web" (devices/), "mobile" (the Gradle build), "tizen"
 * (tv/tizen/, the Samsung TV app), "vega" (tv/vega/, the Vega OS app), "tv" (tv/shared/ and tv/core/, which both TV
 * apps use) or "both".
 */
export function area(file) {
    if (file.startsWith("tv/tizen/")) return file.endsWith(".md") ? "docs" : "tizen";
    if (file.startsWith("tv/vega/")) return file.endsWith(".md") ? "docs" : "vega";
    if (file.startsWith("tv/")) return file.endsWith(".md") ? "docs" : "tv";
    // The website is built from Markdown too: its posts, and the privacy policy behind /privacy/.
    if (file.startsWith("devices/site/") || file === "docs/privacy.md") return "web";
    if (file.endsWith(".md") || file.startsWith("docs/")) return "docs";
    if (file.startsWith("devices/")) return "web";
    if (/^(app|core|data|smoketest|fakeprovider|baselineprofile|gradle|config)\//.test(file)) return "mobile";
    if (file.startsWith(".github/actions/")) return "mobile"; // setup-android and the Robolectric cache
    if (/^[^/]+\.gradle\.kts$/.test(file) || /^(gradle\.properties|gradlew|gradlew\.bat|\.editorconfig)$/.test(file)) {
        return "mobile";
    }
    // Workflows, scripts/, package.json, Prettier's config and the like: anything could depend on them.
    return "both";
}

/**
 * tv/core compiles :core's own files to JavaScript with the Gradle build's versions, so a change there can break the
 * TV apps too.
 */
const buildsTvCore = (file) => /^(core|gradle)\//.test(file) || /^[^/]+\.gradle\.kts$/.test(file) || file === "gradle.properties";

/** Whether the mobile app's jobs, the website's tests and each TV app's job need to run for these files. */
export function needs(files) {
    const list = files.filter(Boolean);
    const areas = new Set(list.map(area));
    const tv = areas.has("tv") || areas.has("both") || list.some(buildsTvCore);
    return {
        mobile: areas.has("mobile") || areas.has("both"),
        web: areas.has("web") || areas.has("both"),
        tizen: tv || areas.has("tizen"),
        vega: tv || areas.has("vega"),
    };
}

if (import.meta.url === `file://${process.argv[1]}`) {
    const { readFileSync } = await import("node:fs");
    const { mobile, web, tizen, vega } = needs(
        readFileSync(0, "utf8")
            .split("\n")
            .map((f) => f.trim()),
    );
    console.log(`mobile=${mobile}\nweb=${web}\ntizen=${tizen}\nvega=${vega}`);
}
