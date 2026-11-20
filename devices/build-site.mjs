// Builds the public website from devices/site/ into devices/public/ (docs/decisions/website.md): the landing page, the
// blog, the privacy policy (from docs/privacy.md), sitemap.xml, the RSS feed and features.json. Run after changing anything in devices/site/, and commit the
// output: node devices/build-site.mjs
// With --check it writes nothing and fails when a generated file is out of date (the test runs the same check).
import { mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { buildSite } from "./lib/site-build.mjs";
import { FEATURE_GROUPS } from "./site/features.mjs";
import { SITE } from "./site/config.mjs";

const root = dirname(fileURLToPath(import.meta.url));
const pub = join(root, "public");

const walk = (dir) =>
    readdirSync(dir).flatMap((name) => {
        const full = join(dir, name);
        return statSync(full).isDirectory() ? walk(full) : [full];
    });

/** The files the site should have, as { path under public/: contents }. */
export function expected() {
    const postsDir = join(root, "site", "posts");
    const posts = walk(postsDir)
        .filter((f) => f.endsWith(".md"))
        .sort()
        .map((f) => ({ path: relative(postsDir, f).split("\\").join("/"), text: readFileSync(f, "utf8") }));
    const template = readFileSync(join(root, "site", "home.html"), "utf8");
    const privacy = readFileSync(join(root, "..", "docs", "privacy.md"), "utf8");
    return buildSite(SITE, { template, groups: FEATURE_GROUPS, posts, privacy });
}

/** Generated pages under public/blog that no source makes any more (images in blog/img are left alone). */
function stale(files) {
    const blog = join(pub, "blog");
    let existing = [];
    try {
        existing = walk(blog);
    } catch {
        return [];
    }
    return existing
        .map((f) => relative(pub, f).split("\\").join("/"))
        .filter((f) => !f.startsWith("blog/img/") && (f.endsWith(".html") || f.endsWith(".xml")) && !(f in files));
}

/** The out-of-date paths, or [] when public/ matches the sources. */
export function check() {
    const files = expected();
    const wrong = Object.entries(files)
        .filter(([path, text]) => {
            try {
                return readFileSync(join(pub, path), "utf8") !== text;
            } catch {
                return true;
            }
        })
        .map(([path]) => path);
    return [...wrong, ...stale(files)];
}

if (import.meta.url === `file://${process.argv[1]}`) {
    if (process.argv.includes("--check")) {
        const wrong = check();
        if (wrong.length) {
            console.error(`Out of date (run node devices/build-site.mjs): ${wrong.join(", ")}`);
            process.exit(1);
        }
        console.log("The website is up to date.");
    } else {
        const files = expected();
        for (const f of stale(files)) rmSync(join(pub, f));
        for (const [path, text] of Object.entries(files)) {
            mkdirSync(dirname(join(pub, path)), { recursive: true });
            writeFileSync(join(pub, path), text);
        }
        console.log(`Wrote ${Object.keys(files).length} files to devices/public/.`);
    }
}
