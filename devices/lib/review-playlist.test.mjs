import assert from "node:assert/strict";
import test from "node:test";
import { reviewPlaylistBody, reviewPlaylistHeaders } from "./review-playlist.mjs";

test("the Play review playlist is a small public M3U with live and film demos", () => {
    const body = reviewPlaylistBody();
    assert.ok(body.startsWith("#EXTM3U"));
    assert.match(body, /group-title="Demo \| Channels"/);
    assert.match(body, /group-title="Demo \| Movies"/);
    assert.match(body, /bipbop_adv_example_fmp4/);
    assert.match(body, /test-streams\.mux\.dev/);
    assert.match(body, /sintel\/trailer\.mp4/);
    assert.match(body, /tears-of-steel/);
    assert.equal((body.match(/^https:\/\//gm) || []).length, 4);
});

test("the review playlist answer is cacheable M3U and not indexed", () => {
    const headers = reviewPlaylistHeaders();
    assert.match(headers["Content-Type"], /mpegurl/i);
    assert.match(headers["Cache-Control"], /max-age=/);
    assert.match(headers["X-Robots-Tag"], /noindex/);
});
