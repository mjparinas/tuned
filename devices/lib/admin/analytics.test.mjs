import { test } from "node:test";
import assert from "node:assert/strict";
import { cleanBatch, cleanPageView, cleanReport, describeAgent, EVENTS, visitorHash } from "./analytics.mjs";

const install = "0123456789abcdef0123456789abcdef";
const now = 1_800_000_000_000;

test("only allow-listed events and properties get through", () => {
    const batch = cleanBatch(
        {
            install,
            device: { build: "play", model: "Pixel 8", kind: "phone", password: "hunter2", server: "http://x" },
            events: [
                { name: "playlist_added", at: now, props: { kind: "xtream", url: "http://provider/get.php" } },
                { name: "watched_channel", at: now, props: { channel: "Sports 1" } },
                { name: "play", at: now, props: { section: "live", title: "Some film" } },
            ],
        },
        now,
    );
    assert.deepEqual(batch.device, { build: "play", model: "Pixel 8", kind: "phone" });
    assert.deepEqual(
        batch.events.map((e) => [e.name, e.props]),
        [
            ["playlist_added", { kind: "xtream" }],
            ["play", { section: "live" }],
        ],
    );
});

test("no event may carry anything that names what's watched or a login", () => {
    const banned = /channel|title|film|show|url|server|user|pass|login|search|query|ip/i;
    for (const [name, fields] of Object.entries(EVENTS))
        for (const key of Object.keys(fields)) assert.doesNotMatch(key, banned, `${name}.${key}`);
});

test("a wrong install id is refused and odd times become now", () => {
    assert.equal(cleanBatch({ install: "nope" }), null);
    const batch = cleanBatch({ install, events: [{ name: "app_open", at: now + 5 * 86_400_000 }] }, now);
    assert.equal(batch.events[0].at, now);
});

test("a report needs words or a crash, and is capped", () => {
    assert.equal(cleanReport({ what: "  " }), null);
    const report = cleanReport({ what: "Guide is empty", log: "x".repeat(100_000), install });
    assert.equal(report.log.length, 60_000);
    assert.equal(report.install, install);
});

const req = (headers = {}, url = "https://tuned-devices.vercel.app/api/pv") => new Request(url, { method: "POST", headers });
const chrome = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";

test("page views keep the referrer's host only, and skip bots and Global Privacy Control", () => {
    const view = cleanPageView({ path: "/tv", referrer: "https://www.reddit.com/r/iptv/comments/1" }, req({ "user-agent": chrome }));
    assert.equal(view.referrer, "reddit.com");
    assert.equal(view.device, "phone");
    assert.equal(cleanPageView({ path: "/" }, req({ "user-agent": "Googlebot/2.1" })), null);
    assert.equal(cleanPageView({ path: "/" }, req({ "user-agent": chrome, "sec-gpc": "1" })), null);
    assert.equal(cleanPageView({ path: "https://evil" }, req({ "user-agent": chrome })), null);
    assert.equal(
        cleanPageView({ path: "/", referrer: "https://tuned-devices.vercel.app/tv" }, req({ "user-agent": chrome })).referrer,
        null,
    );
});

test("a visitor hash changes each day and never holds the address", () => {
    const a = visitorHash("1.2.3.4", chrome, "2026-11-19", "salt");
    assert.notEqual(a, visitorHash("1.2.3.4", chrome, "2026-11-20", "salt"));
    assert.doesNotMatch(a, /1\.2\.3\.4/);
    assert.deepEqual(describeAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605 Version/17 Safari/605"), {
        browser: "Safari",
        os: "macOS",
        device: "desktop",
    });
});
