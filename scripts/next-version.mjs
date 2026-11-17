// Works out the version of the next release from the commits since the last one (Melvin, 2026-11-17: "Auto bump
// semver versions with each update"). Pull request titles, which become the squashed commit subjects, follow
// Conventional Commits:
//   feat: ...                    -> minor (0.1.0 -> 0.2.0)
//   fix: ..., anything else      -> patch (0.1.0 -> 0.1.1)
//   feat!: ... or BREAKING CHANGE -> major (0.1.0 -> 1.0.0)
// Releases are tagged v<x.y.z> (Melvin, 2026-11-17: no build number); older ones were v<x.y.z>-build.<n>, and both
// count as the last release. package.json's version is a floor, so a hand bump there still wins. With no release yet,
// package.json's version is used as is. Prints the version on stdout.
//
// Usage: node scripts/next-version.mjs          (needs the tags and history: actions/checkout with fetch-depth: 0)
//        node scripts/next-version.mjs --code 1.2.3   prints the Android version code for a version
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

const git = (...args) => execFileSync("git", args, { encoding: "utf8" }).trim();
const parse = (v) => v.split(".").map((n) => Number.parseInt(n, 10));
const compare = (a, b) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];

export function bump(version, messages) {
    const [major, minor, patch] = parse(version);
    const breaking = messages.some((m) => /^\w+(\([^)]*\))?!:/m.test(m) || /^BREAKING[ -]CHANGE:/m.test(m));
    if (breaking) return `${major + 1}.0.0`;
    const feature = messages.some((m) => /^feat(\([^)]*\))?:/im.test(m));
    if (feature) return `${major}.${minor + 1}.0`;
    return `${major}.${minor}.${patch + 1}`;
}

/** Android's version code, which must grow with each release: 1.2.3 -> 1002003. Minor and patch stay below 1000. */
export function versionCode(version) {
    const [major, minor, patch] = parse(version);
    if (minor >= 1000 || patch >= 1000) throw new Error(`${version}: minor and patch must be below 1000`);
    return major * 1_000_000 + minor * 1_000 + patch;
}

const releaseTag = /^v\d+\.\d+\.\d+(-build\.\d+)?$/;

/** The newest release among [tags], plain or with the old build suffix. */
export function lastRelease(tags) {
    return tags
        .filter((t) => releaseTag.test(t))
        .sort((a, b) => compare(parse(b.slice(1).split("-")[0]), parse(a.slice(1).split("-")[0])))[0];
}

export function next(floor, lastTag, messages) {
    if (!lastTag) return floor;
    const last = lastTag.replace(/^v/, "").split("-")[0];
    const bumped = bump(last, messages);
    return compare(parse(floor), parse(bumped)) > 0 ? floor : bumped;
}

if (import.meta.url === `file://${process.argv[1]}` && process.argv[2] === "--code") {
    process.stdout.write(String(versionCode(process.argv[3])));
} else if (import.meta.url === `file://${process.argv[1]}`) {
    const floor = JSON.parse(readFileSync("package.json", "utf8")).version;
    const lastTag = lastRelease(git("tag", "--list", "v*").split("\n").filter(Boolean));
    // One entry per commit: the subject, then the body, so "BREAKING CHANGE" in a body counts.
    const messages = lastTag
        ? git("log", "--format=%s%n%b%x00", `${lastTag}..HEAD`)
              .split("\0")
              .map((m) => m.trim())
              .filter(Boolean)
        : [];
    process.stdout.write(next(floor, lastTag, messages));
}
