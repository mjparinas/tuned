// The channel list's rules (public/account/channels/core.mjs, docs/decisions/web.md › The channel list).
import { test } from "node:test";
import assert from "node:assert/strict";
import {
    ago,
    applyFilters,
    genreOf,
    guideSpan,
    hasWord,
    logoSrc,
    NO_FILTERS,
    normalize,
    offers,
    regionName,
    ticks,
    fileName,
    fillLogin,
    fromAppCopy,
    noCopyText,
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
    clickPlan,
    clickMessage,
    vlcDidNotOpen,
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

test("a click opens VLC where it has a link, and always copies the link", () => {
    const url = "http://p.example:8080/live/u/p/1.ts";
    assert.deepEqual(clickPlan(url, "mac", "CBC"), { vlc: `vlc://${url}`, copy: true });
    assert.deepEqual(clickPlan(url, "windows", "CBC"), { vlc: null, copy: true });
    assert.deepEqual(clickPlan(url, "other", "CBC"), { vlc: null, copy: true });
    assert.deepEqual(clickPlan(url, "android", "CBC", "copy"), { vlc: null, copy: true });
    assert.equal(clickPlan("not a link", "mac").vlc, null);
});

test("every click says what it did", () => {
    assert.deepEqual(clickMessage({ name: "CBC", vlc: "vlc://x", copied: true }), {
        tone: "ok",
        text: "Opening CBC in VLC. The link is copied too, to paste into another player.",
    });
    assert.equal(clickMessage({ name: "CBC", vlc: "vlc://x", copied: false }).text, "Opening CBC in VLC.");
    assert.equal(clickMessage({ name: "CBC", vlc: null, copied: true }).text.startsWith("CBC's link copied."), true);
    assert.equal(clickMessage({ name: "CBC", vlc: null, copied: false }).tone, "error");
    assert.match(clickMessage({ name: "", vlc: null, copied: true }).text, /^This channel's link copied/);
    assert.match(vlcDidNotOpen("CBC"), /CBC's link is copied/);
});

test("a channel's .m3u file opens in any player, and names are safe for files", () => {
    const text = m3uFor([{ name: 'A, "B"\nC', group: "News", epgId: "a.ca", logo: "", url: "http://p/1.ts\n" }]);
    assert.equal(text, '#EXTM3U\n#EXTINF:-1 tvg-id="a.ca" group-title="News",A   B C\nhttp://p/1.ts\n');
    assert.equal(fileName('TSN 1: "HD"/East'), "TSN 1 HD East.m3u");
    assert.equal(fileName(""), "channel.m3u");
});

test("the app's copy becomes channels and a guide, with the login put back", () => {
    const copy = {
        v: 1,
        playlist: "74d5b50ccf496f21",
        savedAt: 1_795_201_200_000,
        channels: [
            ["CBC", "Canada", "http://i/cbc.png", "cbc.ca", 1, "http://p:8080/live/{login}/1.ts"],
            ["Odd", "", "", "", 0, "http://p/play?username={user}&password={pass}"],
            ["Plain", "News", "", "", 3, "http://other/stream.m3u8"],
            "not a channel",
        ],
        guide: {
            "cbc.ca": [
                [1_795_204_800, 1_795_208_400, "Later"],
                [1_795_201_200, 1_795_204_800, "Now"],
            ],
            bad: "x",
        },
    };
    const read = fromAppCopy(copy, { username: "me@x", password: "a/b" });
    assert.deepEqual(
        read.channels.map((c) => c.url),
        ["http://p:8080/live/me%40x/a%2Fb/1.ts", "http://p/play?username=me%40x&password=a%2Fb", "http://other/stream.m3u8"],
    );
    assert.deepEqual(read.channels[0], {
        id: 0,
        region: "",
        name: "CBC",
        group: "Canada",
        logo: "http://i/cbc.png",
        epgId: "cbc.ca",
        number: "1",
        url: "http://p:8080/live/me%40x/a%2Fb/1.ts",
    });
    // A newer app's row carries the stable id the website edits with.
    assert.equal(fromAppCopy({ v: 1, channels: [["CBC", "", "", "", 1, "http://a", "CA", 0, 42]], guide: {} }, null).channels[0].id, 42);
    assert.equal(read.channels[1].number, "");
    assert.deepEqual(
        read.guide.get("cbc.ca").map((p) => [p.title, p.start]),
        [
            ["Now", 1_795_201_200_000],
            ["Later", 1_795_204_800_000],
        ],
    );
    assert.equal(read.guide.has("bad"), false);
    assert.equal(read.savedAt, 1_795_201_200_000);
    // Without the login, the links that need it are left out rather than broken.
    assert.deepEqual(
        fromAppCopy(copy, null).channels.map((c) => c.name),
        ["Plain"],
    );
    assert.equal(fromAppCopy({ v: 2 }, null), null);
    assert.equal(fillLogin("http://a/b", null), "http://a/b");
});

test("how long ago, in words", () => {
    const now = 10 * 86_400_000;
    assert.equal(ago(now - 10_000, now), "just now");
    assert.equal(ago(now - 60_000, now), "1 minute ago");
    assert.equal(ago(now - 3 * 3_600_000, now), "3 hours ago");
    assert.equal(ago(now - 3 * 86_400_000, now), "3 days ago");
});

test("a newer app's copy brings each channel's country and each programme's kind, and leaves adult channels in the app", () => {
    const copy = {
        v: 1,
        channels: [
            ["TSN 1 FHD", "CA| Sports", "", "tsn1.ca", 1, "http://p/1.ts", "ca", 0],
            ["Late night", "XXX", "", "", 2, "http://p/2.ts", "", 1],
        ],
        guide: { "tsn1.ca": [[1_795_201_200, 1_795_204_800, "Leafs vs Habs", 1]] },
    };
    const read = fromAppCopy(copy, null);
    assert.deepEqual(
        read.channels.map((c) => [c.name, c.region]),
        [["TSN 1 FHD", "CA"]],
    );
    assert.equal(read.guide.get("tsn1.ca")[0].genre, 1);
});

test("words as the app matches them: a starred word starts a word, a phrase is in order, accents don't count", () => {
    assert.equal(normalize("Télé-Québec  HD"), "tele quebec hd");
    assert.equal(hasWord("Sportsnet One", ["sport*"]), true);
    assert.equal(hasWord("CNN HD", ["hd"]), true);
    assert.equal(hasWord("CNN FHD", ["hd"]), false);
    assert.equal(hasWord("US| Premier League 1", ["premier league"]), true);
    assert.equal(hasWord("League Premier", ["premier league"]), false);
});

const sample = [
    { name: "TSN 1 FHD", group: "CA| Sports", region: "CA", epgId: "tsn1", hasGuide: true },
    { name: "CNN HD", group: "US| News", region: "US", epgId: "cnn", hasGuide: true },
    { name: "Treehouse", group: "CA| Kids", region: "CA", epgId: "", hasGuide: false },
    { name: "Sky Cinema 4K", group: "UK| Movies", region: "UK", epgId: "sky", hasGuide: true },
];
const on = { tsn1: { title: "Leafs vs Habs", genre: 0 }, cnn: { title: "Newsroom", genre: 3 }, sky: { title: "Dune", genre: 2 } };
const nowOf = (c) => on[c.epgId] ?? null;
const names = (list) => list.map((c) => c.name);

test("Live TV's filters narrow together, as in the app", () => {
    assert.equal(applyFilters(sample, NO_FILTERS, nowOf).length, 4);
    assert.deepEqual(names(applyFilters(sample, { ...NO_FILTERS, topic: "sports" }, nowOf)), ["TSN 1 FHD"]);
    assert.deepEqual(names(applyFilters(sample, { ...NO_FILTERS, topic: "kids" }, nowOf)), ["Treehouse"]);
    assert.deepEqual(names(applyFilters(sample, { ...NO_FILTERS, quality: "hd" }, nowOf)), ["CNN HD"]);
    assert.deepEqual(names(applyFilters(sample, { ...NO_FILTERS, quality: "uhd" }, nowOf)), ["Sky Cinema 4K"]);
    assert.deepEqual(names(applyFilters(sample, { ...NO_FILTERS, country: "CA" }, nowOf)), ["TSN 1 FHD", "Treehouse"]);
    assert.deepEqual(names(applyFilters(sample, { ...NO_FILTERS, withGuide: true, country: "CA" }, nowOf)), ["TSN 1 FHD"]);
    // A match between two sides is sports when the guide doesn't say.
    assert.deepEqual(names(applyFilters(sample, { ...NO_FILTERS, onNow: 1 }, nowOf)), ["TSN 1 FHD"]);
    assert.deepEqual(names(applyFilters(sample, { ...NO_FILTERS, onNow: 2 }, nowOf)), ["Sky Cinema 4K"]);
    assert.deepEqual(names(applyFilters(sample, { ...NO_FILTERS, query: "dune" }, nowOf)), ["Sky Cinema 4K"]);
    assert.deepEqual(names(applyFilters(sample, { ...NO_FILTERS, group: "US| News" }, nowOf)), ["CNN HD"]);
    assert.equal(genreOf({ title: "Arsenal v Chelsea" }), 1);
    assert.equal(genreOf({ title: "Movie night" }), 0);
});

test("only the filters that would find something are offered, countries by name", () => {
    const o = offers(sample, nowOf);
    assert.deepEqual(
        o.countries.map((c) => [c.tag, c.name, c.count]),
        [
            ["CA", "Canada", 2],
            ["UK", "United Kingdom", 1],
            ["US", "United States", 1],
        ],
    );
    assert.deepEqual(
        o.topics.map((t) => t.key),
        ["sports", "news", "kids", "movies"],
    );
    assert.deepEqual(
        o.qualities.map((q) => q.key),
        ["uhd", "fhd", "hd"],
    );
    assert.deepEqual(
        o.genres.map((g) => g.code),
        [1, 2, 3],
    );
    assert.equal(o.guide, true);
    assert.equal(regionName("EN"), "English");
    assert.equal(regionName("de"), "Germany");
    assert.equal(regionName("ZZZ"), "ZZZ");
});

test("an http logo goes through the logo relay on an https page; https ones and anything else don't", () => {
    assert.equal(logoSrc("https://i.example/a.png"), "https://i.example/a.png");
    assert.equal(logoSrc("http://i.example/a.png"), "/api/account/logo?u=http%3A%2F%2Fi.example%2Fa.png");
    assert.equal(logoSrc("http://i.example/a.png", "http:"), "http://i.example/a.png");
    assert.equal(logoSrc("data:image/png;base64,xx"), "");
    assert.equal(logoSrc(""), "");
});

test("the guide spans from just before now to its last programme, at most a day and a bit", () => {
    const now = Date.UTC(2026, 10, 21, 19, 10);
    const guide = new Map([["a", [{ start: now, stop: now + 10 * 3_600_000 }]]]);
    const span = guideSpan(guide, now);
    assert.equal(span.from, Date.UTC(2026, 10, 21, 18, 30));
    assert.equal(span.to, Date.UTC(2026, 10, 22, 5, 30));
    assert.equal(guideSpan(new Map(), now).to, Date.UTC(2026, 10, 21, 22, 30));
    const long = new Map([["a", [{ start: now, stop: now + 90 * 3_600_000 }]]]);
    assert.equal(guideSpan(long, now).to - guideSpan(long, now).from, 26 * 3_600_000);
    assert.deepEqual(ticks(Date.UTC(2026, 10, 21, 18, 10), Date.UTC(2026, 10, 21, 19, 31)), [
        Date.UTC(2026, 10, 21, 18, 30),
        Date.UTC(2026, 10, 21, 19, 0),
        Date.UTC(2026, 10, 21, 19, 30),
    ]);
});

test("a playlist with no channels in the account says so, why, and what to do", () => {
    const none = noCopyText("none");
    assert.equal(none.title, "No channels backed up for this playlist yet");
    assert.match(none.fix, /Back up now/);
    assert.match(noCopyText(undefined).title, /No channels backed up/);
    assert.match(noCopyText("empty").fix, /Refresh the playlist/);
    // Our own server failing isn't "not backed up".
    assert.doesNotMatch(noCopyText("error").title, /backed up for/);
    assert.equal(noCopyText("too many").fix, "Try again later.");
});
