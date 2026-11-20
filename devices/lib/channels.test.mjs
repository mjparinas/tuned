// The channel list's rules (public/account/channels/core.mjs, docs/decisions/web.md › The channel list).
import { test } from "node:test";
import assert from "node:assert/strict";
import {
    fileName,
    filterChannels,
    gridPlace,
    gridSlots,
    groupsOf,
    GuideReader,
    headerGuide,
    m3uFor,
    nowNext,
    parseM3u,
    platformOf,
    progress,
    vlcLink,
    xmlText,
    xmltvTime,
    xtreamChannels,
    xtreamLiveUrl,
    xtreamPlaylistUrl,
} from "../public/account/channels/core.mjs";

const M3U = `#EXTM3U url-tvg="http://epg.example/guide.xml.gz,http://other.example/b.xml"
#EXTINF:-1 tvg-id="cbc.ca" tvg-name="CBC, Toronto" tvg-logo="http://img.example/cbc.png" group-title="Canada",CBC Toronto
http://p.example/live/u/p/1.ts
#EXTINF:-1 tvg-id="tsn1.ca" group-title="Sports",TSN 1, HD
http://p.example/live/u/p/2.ts
#EXTINF:-1,
#EXTGRP:Extras
http://p.example/live/u/p/3.ts
`;

test("an M3U gives every channel with its name, group, logo and guide id", () => {
    const list = parseM3u(M3U);
    assert.equal(list.length, 3);
    assert.deepEqual(list[0], {
        name: "CBC Toronto",
        group: "Canada",
        logo: "http://img.example/cbc.png",
        epgId: "cbc.ca",
        number: "",
        url: "http://p.example/live/u/p/1.ts",
    });
    // A comma in the name stays; a comma inside an attribute's quotes isn't the name's start.
    assert.equal(list[1].name, "TSN 1, HD");
    // No name falls back to the link; #EXTGRP gives the group.
    assert.equal(list[2].name, "http://p.example/live/u/p/3.ts");
    assert.equal(list[2].group, "Extras");
});

test("the guide a playlist names in its header", () => {
    assert.equal(headerGuide(M3U), "http://epg.example/guide.xml.gz");
    assert.equal(headerGuide('#EXTM3U x-tvg-url="https://g.example/x.xml"\n'), "https://g.example/x.xml");
    assert.equal(headerGuide("#EXTINF:-1,A\nhttp://a\n"), "");
});

test("Xtream channels get their group's name and a .ts link from the address as saved", () => {
    const list = xtreamChannels(
        [{ category_id: "7", category_name: "News" }],
        [
            { stream_id: 11, name: " CNN ", category_id: 7, stream_icon: "http://i/cnn.png", epg_channel_id: "cnn.us", num: 3 },
            { stream_id: 12, name: "", category_id: "9" },
            { name: "no id" },
        ],
        "http://p.example:8080",
        "me@x",
        "p/w",
    );
    assert.equal(list.length, 2);
    assert.deepEqual(list[0], {
        name: "CNN",
        group: "News",
        logo: "http://i/cnn.png",
        epgId: "cnn.us",
        number: "3",
        url: "http://p.example:8080/live/me%40x/p%2Fw/11.ts",
    });
    assert.equal(list[1].name, "Channel 12");
    assert.equal(list[1].group, "");
    assert.equal(xtreamLiveUrl("http://a", "u", "p", 5, "m3u8"), "http://a/live/u/p/5.m3u8");
    assert.equal(xtreamPlaylistUrl("http://a", "u", "p"), "http://a/get.php?username=u&password=p&type=m3u_plus&output=ts");
});

test("groups in first-seen order, and search by name or what's on, accents ignored", () => {
    const list = parseM3u(M3U);
    assert.deepEqual(groupsOf(list), [
        { name: "Canada", count: 1 },
        { name: "Sports", count: 1 },
        { name: "Extras", count: 1 },
    ]);
    const nowTitle = (c) => (c.epgId === "tsn1.ca" ? "Hockey: Montréal at Toronto" : "");
    assert.deepEqual(
        filterChannels(list, { query: "montreal hockey", nowTitle }).map((c) => c.epgId),
        ["tsn1.ca"],
    );
    assert.equal(filterChannels(list, { group: "Canada" }).length, 1);
    assert.equal(filterChannels(list, {}).length, 3);
});

test("XMLTV times with and without an offset", () => {
    assert.equal(xmltvTime("20261120190000 +0000"), Date.UTC(2026, 10, 20, 19, 0, 0));
    assert.equal(xmltvTime("20261120140000 -0500"), Date.UTC(2026, 10, 20, 19, 0, 0));
    assert.equal(xmltvTime("202611201900"), Date.UTC(2026, 10, 20, 19, 0, 0));
    assert.ok(Number.isNaN(xmltvTime("soon")));
    assert.equal(xmlText("<![CDATA[A & B]]>"), "A & B");
    assert.equal(xmlText("Caf&#233; &amp; &#x41; &nope;"), "Café & A &nope;");
});

const GUIDE = `<?xml version="1.0"?><tv>
<channel id="CBC.ca"><display-name>CBC</display-name></channel>
<programme start="20261120180000 +0000" stop="20261120190000 +0000" channel="cbc.ca"><title>Old news</title></programme>
<programme start="20261120190000 +0000" stop="20261120200000 +0000" channel="CBC.ca"><title lang="en">The National &amp; more</title><desc>Tonight's news.</desc></programme>
<programme start="20261120200000 +0000" stop="20261120210000 +0000" channel="cbc.ca"><title>Hockey Night</title></programme>
<programme start="20261120200000 +0000" stop="20261120210000 +0000" channel="cbc.ca"><title>Hockey Night</title></programme>
<programme start="20261120190000 +0000" stop="20261120200000 +0000" channel="other"><title>Not ours</title></programme>
<programme start="20261121190000 +0000" stop="20261121200000 +0000" channel="cbc.ca"><title>Tomorrow</title></programme>
</tv>`;

test("the guide is read in pieces, keeping only our channels within the window", () => {
    const from = Date.UTC(2026, 10, 20, 19, 0);
    const to = from + 12 * 3_600_000;
    for (const size of [1, 7, 64, GUIDE.length]) {
        const reader = new GuideReader(new Set(["cbc.ca"]), from, to);
        for (let i = 0; i < GUIDE.length; i += size) reader.feed(GUIDE.slice(i, i + size));
        const guide = reader.done();
        assert.deepEqual([...guide.keys()], ["cbc.ca"], `piece size ${size}`);
        assert.deepEqual(
            guide.get("cbc.ca").map((p) => p.title),
            ["The National & more", "Hockey Night"],
            `piece size ${size}`,
        );
        assert.equal(guide.get("cbc.ca")[0].desc, "Tonight's news.");
    }
});

test("now and next, progress and the grid's places", () => {
    const at = (h) => Date.UTC(2026, 10, 20, h, 0);
    const list = [
        { start: at(19), stop: at(20), title: "A" },
        { start: at(20), stop: at(21), title: "B" },
    ];
    assert.deepEqual(
        Object.values(nowNext(list, at(19) + 1)).map((p) => p?.title),
        ["A", "B"],
    );
    assert.deepEqual(
        Object.values(nowNext(list, at(18))).map((p) => p?.title),
        [undefined, "A"],
    );
    assert.deepEqual(nowNext(list, at(22)), { now: null, next: null });
    assert.deepEqual(nowNext(null, at(19)), { now: null, next: null });
    assert.equal(progress(list[0], at(19) + 1_800_000), 0.5);
    const slots = gridSlots(at(19) + 600_000, 3);
    assert.equal(slots.length, 6);
    assert.equal(slots[0], at(19));
    assert.deepEqual(gridPlace(list[1], at(19), at(22)), { left: 1 / 3, width: 1 / 3 });
    assert.equal(gridPlace(list[0], at(20), at(22)), null);
});

test("VLC's own link on Android, iPhone and Mac; none on Windows, where the .m3u file opens it", () => {
    const url = "http://p.example:8080/live/u/p/1.ts";
    assert.equal(
        vlcLink(url, "android", "CBC"),
        "intent://p.example:8080/live/u/p/1.ts#Intent;scheme=http;package=org.videolan.vlc;type=video/*;S.title=CBC;end",
    );
    assert.equal(vlcLink(url, "ios"), `vlc-x-callback://x-callback-url/stream?url=${encodeURIComponent(url)}`);
    assert.equal(vlcLink(url, "mac"), `vlc://${url}`);
    assert.equal(vlcLink(url, "windows"), null);
    assert.equal(vlcLink("not a link", "android"), null);
    assert.equal(platformOf("Mozilla/5.0 (Linux; Android 14; Pixel 8)"), "android");
    assert.equal(platformOf("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)"), "ios");
    assert.equal(platformOf("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)", 5), "ios");
    assert.equal(platformOf("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)", 0), "mac");
    assert.equal(platformOf("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"), "windows");
});

test("a channel's .m3u file opens in any player, and names are safe for files", () => {
    const text = m3uFor([{ name: 'A, "B"\nC', group: "News", epgId: "a.ca", logo: "", url: "http://p/1.ts\n" }]);
    assert.equal(text, '#EXTM3U\n#EXTINF:-1 tvg-id="a.ca" group-title="News",A   B C\nhttp://p/1.ts\n');
    assert.equal(fileName('TSN 1: "HD"/East'), "TSN 1 HD East.m3u");
    assert.equal(fileName(""), "channel.m3u");
});
