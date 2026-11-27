import assert from "node:assert/strict";
import { test } from "node:test";
import { androidId, androidIdHex, fnv64, fnv64Hex, hexToId, idToHex, linkTail, xtreamShowLink } from "./stableid.ts";

test("works out Android's ids as core/StableId does", () => {
    // StableIdTest's and TunedCoreTest's values.
    assert.equal(fnv64(""), "-3750763034362895579");
    assert.equal(androidId("live", "http://h:8080/live/u/p/7.ts", "Sky News"), fnv64("0|7|Sky News"));
    assert.equal(fnv64("0|7|Sky News"), "-424957186676999454");
    assert.equal(androidId("movie", "http://h/movie/u/p/42.mkv?token=1", "Café"), "3492377018087582336");
    assert.equal(androidId("show", xtreamShowLink(9), "A Show"), fnv64("2|xtream-series:9|A Show"));
});

test("takes the link's last part without its extension, query or headers", () => {
    assert.equal(linkTail("http://h/live/u/p/1234.m3u8"), "1234");
    assert.equal(linkTail("http://h/a/b/news.ts?x=1|User-Agent=Tuned"), "news");
    assert.equal(linkTail("http://h/a/b/stream"), "stream");
    assert.equal(linkTail("http://h/a/v1.2/x.y.ts"), "x.y");
});

test("the quick hex form matches, for any text", () => {
    assert.equal(fnv64Hex(""), "cbf29ce484222325");
    for (const text of ["a", "0|7|Sky News", "2|xtream-series:9|Ünïcødé 📺", "x".repeat(500)]) {
        assert.equal(idToHex(fnv64(text)), fnv64Hex(text));
        assert.equal(hexToId(fnv64Hex(text)), fnv64(text));
    }
    assert.equal(androidIdHex("live", "http://h/live/u/p/7.ts", "Sky News"), idToHex("-424957186676999454"));
    assert.equal(idToHex("not a number"), null);
});
