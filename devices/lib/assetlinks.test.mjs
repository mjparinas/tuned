import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

// Android verifies the /tv app link against this file on both hosts. Copies from Play are signed by Play App Signing's key,
// the GitHub APK by the release key, so each must be listed or that build's link opens the web page instead.
const links = JSON.parse(readFileSync(new URL("../public/.well-known/assetlinks.json", import.meta.url), "utf8"));
const GITHUB_KEY = "AD:40:95:4F:4E:BF:CB:9A:2B:51:00:B6:C6:F4:2F:D1:D0:44:6B:69:04:FC:DB:E0:2D:96:39:93:7D:FF:53:61";
const PLAY_KEY = "1B:D7:B7:CF:F7:A8:85:33:13:DA:88:44:A7:E3:BF:EE:34:45:7F:E9:46:F4:B7:1A:C3:94:E7:2B:3C:3E:B3:1B";

const keysFor = (pkg) =>
    links
        .filter((s) => s.relation.includes("delegate_permission/common.handle_all_urls") && s.target.package_name === pkg)
        .flatMap((s) => s.target.sha256_cert_fingerprints);

test("the app is linked with both the GitHub release key and Play App Signing's key", () => {
    assert.ok(keysFor("com.tunedplayer.app").includes(GITHUB_KEY));
    assert.ok(keysFor("com.tunedplayer.app").includes(PLAY_KEY));
});

test("1.13 copies (app.tuned) keep their link", () => {
    assert.ok(keysFor("app.tuned").includes(GITHUB_KEY));
});

test("every fingerprint is a SHA-256 in upper-case hex pairs", () => {
    for (const s of links) for (const f of s.target.sha256_cert_fingerprints) assert.match(f, /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/);
});
