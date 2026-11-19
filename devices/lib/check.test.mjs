// The provider check's rules (public/check/verdict.mjs, docs/decisions/web.md).
import { test } from "node:test";
import assert from "node:assert/strict";
import {
    access,
    apiUrl,
    codecMime,
    codecName,
    httpsVariant,
    isMixed,
    liveUrl,
    looksLikePortal,
    parseM3u,
    parseServer,
    redact,
    streamKind,
    verdict,
} from "../public/check/verdict.mjs";

test("reads a bare host, a port, a path and an Xtream link", () => {
    assert.equal(parseServer("example.com:8080").base, "http://example.com:8080");
    assert.equal(parseServer("https://example.com/xc/").base, "https://example.com/xc");
    const link = parseServer("http://example.com:8080/get.php?username=a%20b&password=p%26w&type=m3u_plus");
    assert.equal(link.base, "http://example.com:8080");
    assert.equal(link.username, "a b");
    assert.equal(link.password, "p&w");
    assert.equal(link.playlistLink, null);
    const m3u = parseServer("https://lists.example.org/mine.m3u");
    assert.equal(m3u.username, null);
    assert.equal(m3u.playlistLink, "https://lists.example.org/mine.m3u");
    assert.equal(parseServer(""), null);
    assert.equal(parseServer("ftp://example.com"), null);
});

test("MAC portals are recognised", () => {
    assert.ok(looksLikePortal("http://example.com/stalker_portal/"));
    assert.ok(looksLikePortal("http://example.com:8080/c/"));
    assert.ok(!looksLikePortal("http://example.com:8080"));
});

test("Xtream links encode the login", () => {
    assert.equal(
        apiUrl("http://h", "u", "p&q", "get_live_streams", { category_id: 3 }),
        "http://h/player_api.php?username=u&password=p%26q&action=get_live_streams&category_id=3",
    );
    assert.equal(liveUrl("http://h", "u", "p", 7, "m3u8"), "http://h/live/u/p/7.m3u8");
});

test("the https twin uses the provider's https port", () => {
    const server = parseServer("http://example.com:8080/xc");
    assert.equal(httpsVariant(server, null), "https://example.com/xc");
    assert.equal(httpsVariant(server, { https_port: "8443" }), "https://example.com:8443/xc");
    assert.equal(httpsVariant(server, { https_port: "443" }), "https://example.com/xc");
    assert.equal(httpsVariant(parseServer("https://example.com"), null), null);
});

test("M3U entries, and what plays each link", () => {
    const text = '#EXTM3U\n#EXTINF:-1 group-title="News",Channel One\nhttp://h/1.ts\n#EXTINF:-1,Film\r\nhttp://h/movie/2.mkv\n';
    assert.deepEqual(parseM3u(text), [
        { name: "Channel One", group: "News", url: "http://h/1.ts" },
        { name: "Film", group: "", url: "http://h/movie/2.mkv" },
    ]);
    assert.equal(parseM3u(text, 1).length, 1);
    assert.equal(streamKind("http://h/live/u/p/1.m3u8?x=1"), "hls");
    assert.equal(streamKind("http://h/live/u/p/1"), "ts");
    assert.equal(streamKind("http://h/movie/u/p/1.MKV"), "file");
});

test("why a request failed", () => {
    assert.equal(access({ cors: true }), "ok");
    assert.equal(access({ cors: false, opaque: false, mixed: true, insecureAllowed: false }), "insecure");
    assert.equal(access({ cors: false, opaque: true, mixed: true, insecureAllowed: true }), "cors");
    assert.equal(access({ cors: false, opaque: false, mixed: false }), "unreachable");
    assert.ok(isMixed("http://h", "https:"));
    assert.ok(!isMixed("https://h", "https:"));
    assert.ok(!isMixed("http://h", "http:"));
});

test("codec names", () => {
    assert.equal(codecMime("h264"), "avc1.640028");
    assert.equal(codecMime("E-AC-3"), "ec-3");
    assert.equal(codecMime("nope"), null);
    assert.equal(codecName("avc1.64001f"), "H.264");
    assert.equal(codecName("mp4a.40.2"), "AAC");
    assert.equal(codecName("ac-3"), "AC-3 (Dolby)");
});

test("the login never shows", () => {
    assert.equal(redact("http://h/live/me/p%26w/1.ts", "p&w", "me"), "http://h/live/•••/•••/1.ts");
    assert.equal(redact("nothing", ""), "nothing");
});

test("one answer from the facts", () => {
    assert.equal(verdict({ portal: true }), "portal");
    assert.equal(verdict({ api: "ok", login: "wrong" }), "login");
    assert.equal(verdict({ api: "cors" }), "blocked");
    assert.equal(verdict({ api: "ok", live: "cors" }), "blocked");
    assert.equal(verdict({ api: "insecure" }), "allow-insecure");
    assert.equal(verdict({ api: "insecure", safari: true }), "not-safari");
    assert.equal(verdict({ api: "unreachable" }), "unreachable");
    assert.equal(verdict({ api: "ok", login: "ok", live: "codec" }), "codec");
    assert.equal(verdict({ api: "ok", login: "ok", live: "ok", needsInsecure: true }), "works-after-setting");
    assert.equal(verdict({ api: "ok", login: "ok", live: "ok", needsInsecure: true, safari: true }), "not-safari");
    assert.equal(verdict({ api: "ok", login: "ok" }), "list-only");
    assert.equal(verdict({ api: "ok", login: "ok", live: "ok" }), "works");
});
