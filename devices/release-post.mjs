// Writes the blog post for a release (docs/decisions/website.md), from the release's GitHub notes, then rebuilds the
// site. Release (release.yml) runs it after publishing each GitHub release, so every release gets its post the same day.
//   node devices/release-post.mjs v1.14.0 2026-11-20 notes.md
// An existing post for that version is kept as it is, so a hand-edited post is never overwritten.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { releasePost, releasePostPath } from "./lib/site-build.mjs";

const [tag, date, notesFile] = process.argv.slice(2);
if (!/^v\d+\.\d+\.\d+/.test(tag ?? "") || !/^\d{4}-\d{2}-\d{2}$/.test(date ?? "") || !notesFile) {
    console.error("Usage: node devices/release-post.mjs v1.14.0 2026-11-20 notes.md");
    process.exit(2);
}
const root = dirname(fileURLToPath(import.meta.url));
const file = join(root, "site", "posts", releasePostPath(tag));
if (existsSync(file)) {
    console.log(`${file} already exists; left as it is.`);
} else {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, releasePost({ tag, date, notes: readFileSync(notesFile, "utf8") }));
    console.log(`Wrote ${file}.`);
}
