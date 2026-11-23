// The web player's play tries (public/watch/player.mjs, docs/decisions/web.md).
import { test } from "node:test";
import assert from "node:assert/strict";
import { httpsStream, playTries } from "../public/watch/player.mjs";

test("an http stream gets an https twin first on an https page", () => {
    const url = "http://provider.example/live/u/p/1.ts";
    assert.equal(httpsStream(url), "https://provider.example/live/u/p/1.ts");
    const tries = playTries(url, "https:");
    assert.equal(tries[0].url, "https://provider.example/live/u/p/1.ts");
    assert.equal(tries[0].how, "native");
    assert.ok(tries.some((t) => t.url === url && t.how === "ts"));
});

test("an https stream is tried as-is", () => {
    const url = "https://provider.example/live/u/p/1.m3u8";
    assert.equal(httpsStream(url), null);
    const tries = playTries(url, "https:");
    assert.deepEqual(
        tries.map((t) => t.how),
        ["native", "hls"],
    );
    assert.ok(tries.every((t) => t.url === url));
});

test("an http page does not invent an https twin first", () => {
    const url = "http://provider.example/live/u/p/1.ts";
    const tries = playTries(url, "http:");
    assert.equal(tries[0].url, url);
});
