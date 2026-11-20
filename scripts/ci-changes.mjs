// Which parts of CI a change needs (Melvin, 2026-11-19: "if there are only changes to the web app, only build the
// webapp and skip mobile builds and vice versa", and "dont run ci if there are only markdown file changes").
// ci.yml pipes the changed files in, one per line, and gates its jobs on what this prints.

/** What a changed file belongs to: "docs" (needs nothing), "web" (devices/), "mobile" (the Gradle build) or "both". */
export function area(file) {
    // The website is built from Markdown too: its posts, and the privacy policy behind /privacy/.
    if (file.startsWith("devices/site/") || file === "docs/privacy.md") return "web";
    if (file.endsWith(".md") || file.startsWith("docs/")) return "docs";
    if (file.startsWith("devices/")) return "web";
    if (/^(app|core|smoketest|fakeprovider|baselineprofile|gradle|config)\//.test(file)) return "mobile";
    if (file.startsWith(".github/actions/")) return "mobile"; // setup-android and the Robolectric cache
    if (/^[^/]+\.gradle\.kts$/.test(file) || /^(gradle\.properties|gradlew|gradlew\.bat|\.editorconfig)$/.test(file)) {
        return "mobile";
    }
    // Workflows, scripts/, package.json, Prettier's config and the like: anything could depend on them.
    return "both";
}

/** Whether the mobile app's jobs and the website's tests need to run for these files. */
export function needs(files) {
    const areas = new Set(files.filter(Boolean).map(area));
    return {
        mobile: areas.has("mobile") || areas.has("both"),
        web: areas.has("web") || areas.has("both"),
    };
}

if (import.meta.url === `file://${process.argv[1]}`) {
    const { readFileSync } = await import("node:fs");
    const { mobile, web } = needs(
        readFileSync(0, "utf8")
            .split("\n")
            .map((f) => f.trim()),
    );
    console.log(`mobile=${mobile}\nweb=${web}`);
}
