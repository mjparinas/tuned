import assert from "node:assert/strict";
import { test } from "node:test";
import { gunzipSync, gzipSync } from "node:zlib";
// The phone page's own sealer (devices/public/tv/seal.js), so the TV opens exactly what phones send.
// @ts-expect-error plain JavaScript
import { pack, seal } from "../../../devices/public/tv/seal.js";
import { b64, unb64, utf8Decode, utf8Encode, Utf8Stream } from "./bytes.ts";
import { checkDigits, displayCode, newKeys, normalizeCode, NotOpened, openBox, unpack } from "./handoff.ts";
import { fromHandoff, fromLink, playlistName } from "./login.ts";
import { loadEpisodes, loadPlaylist, LoadError, type Step } from "./playlist.ts";
import { XmltvReader, parseTime } from "./xmltv.ts";
import { OneConnection } from "./player.ts";
import { TunedAccount } from "./account.ts";
import { MemoryStore, type CryptoLike } from "./platform.ts";
import type { Item } from "./catalog.ts";
import { core } from "./core.ts";
import { fakeFetch } from "./test-fetch.ts";

const webCrypto = globalThis.crypto as unknown as CryptoLike;
const gunzip = async (bytes: Uint8Array) => new Uint8Array(gunzipSync(bytes));

test("utf-8 and base64 round trip, even split mid-character", () => {
    const text = "Télé-Québec 🇬🇧 日本";
    assert.equal(utf8Decode(utf8Encode(text)), text);
    const bytes = utf8Encode(text);
    const stream = new Utf8Stream();
    let out = "";
    for (let i = 0; i < bytes.length; i += 3) out += stream.decode(bytes.subarray(i, i + 3));
    out += stream.decode(new Uint8Array(0), true);
    assert.equal(out, text);
    const raw = Uint8Array.from([0, 1, 2, 250, 251, 252, 253]);
    assert.deepEqual(unb64(b64(raw)), raw);
    assert.equal(b64(raw), Buffer.from(raw).toString("base64url"));
});

test("opens a box the phone page sealed, plain or gzipped, and refuses one for another code", async () => {
    const keys = await newKeys(webCrypto);
    const code = "K7QF29MD";
    const json = JSON.stringify({ version: 1, playlists: [{ server: "tv.example:8080", username: "u", password: "p", name: "Home" }] });
    const plain = await openBox(await seal(pack(json), code, keys.publicKey), code, keys, webCrypto);
    assert.equal(await unpack(plain, gunzip), json);
    const zipped = Uint8Array.from([1, ...gzipSync(Buffer.from(json))]);
    assert.equal(await unpack(await openBox(await seal(zipped, code, keys.publicKey), code, keys, webCrypto), gunzip), json);
    await assert.rejects(openBox(await seal(pack(json), "ABCDEFGH", keys.publicKey), code, keys, webCrypto), NotOpened);
    assert.equal(normalizeCode("k7qf-29md"), code);
    assert.equal(normalizeCode("K7QF29M0"), null);
    assert.equal(displayCode(code), "K7QF-29MD");
    assert.match(await checkDigits(keys.publicKey, webCrypto), /^\d{4}$/);
});

test("reads logins from the phone and from pasted links", () => {
    const logins = fromHandoff(
        JSON.stringify({
            playlists: [
                { server: "tv.example:8080", username: "u", password: "p", playlistUrl: "", name: "" },
                {
                    server: "",
                    username: "",
                    password: "",
                    playlistUrl: "http://lists.example/x.m3u",
                    guideUrl: "http://lists.example/x.xml",
                    name: "Free",
                },
                { server: "tv.example:8080", username: "u", password: "p" },
            ],
        }),
    );
    assert.equal(logins.length, 2);
    assert.deepEqual(logins[0], { kind: "xtream", server: "http://tv.example:8080", username: "u", password: "p", name: "" });
    assert.equal(playlistName(logins[0]), "tv.example");
    assert.equal(logins[1].kind, "m3u");
    assert.deepEqual(fromLink("http://tv.example:8080/get.php?username=me&password=p%20w&type=m3u_plus"), {
        kind: "xtream",
        server: "http://tv.example:8080",
        username: "me",
        password: "p w",
        name: "",
    });
    assert.equal(fromLink("lists.example/x.m3u")?.kind, "m3u");
    assert.deepEqual(fromHandoff("not json"), []);
});

const xtreamServer = (accept = true) =>
    fakeFetch((url) => {
        if (url.pathname !== "/player_api.php") return undefined;
        const action = url.searchParams.get("action");
        if (!action) return { body: { user_info: { auth: accept ? 1 : 0 } } };
        const lists: Record<string, unknown> = {
            get_live_categories: [{ category_id: "1", category_name: "UK | News" }],
            get_live_streams: [
                { stream_id: 10, name: "UK: Sky News", stream_icon: "http://l/sky.png", category_id: "1", epg_channel_id: "sky.uk" },
                { stream_id: 11, name: "UK: BBC News HD", category_id: "1" },
            ],
            get_vod_categories: [{ category_id: "2", category_name: "Movies" }],
            get_vod_streams: [
                { stream_id: 20, name: "Heat (1995)", category_id: "2", container_extension: "mkv", rating: "8.3", added: "1700000000" },
            ],
            get_series_categories: [{ category_id: "3", category_name: "Shows" }],
            get_series: [{ series_id: 30, name: "The Wire", cover: "http://l/wire.jpg", category_id: "3" }],
            get_series_info: {
                episodes: { "1": [{ id: "301", title: "The Target", season: 1, episode_num: 1, container_extension: "mp4" }] },
            },
        };
        return { body: lists[action] ?? [] };
    });

test("reads an Xtream server through its API, step by step, with Android's links and regions", async () => {
    const fetch = xtreamServer();
    const steps: Step[] = [];
    const items: Item[] = [];
    const login = { kind: "xtream", server: "tv.example:8080", username: "u", password: "p", name: "" } as const;
    const result = await loadPlaylist(fetch, login, { onStep: (s) => steps.push(s), onItems: (batch) => void items.push(...batch) });
    assert.deepEqual([result.live, result.movies, result.shows], [2, 1, 1]);
    assert.deepEqual(
        [...new Set(steps.map((s) => `${s.number}/${s.count} ${s.id}`))],
        ["1/4 login", "2/4 live", "3/4 movies", "4/4 shows"],
    );
    const sky = items.find((i) => i.name === "UK: Sky News")!;
    assert.equal(sky.url, "http://tv.example:8080/live/u/p/10.ts");
    assert.equal(sky.region, "UK");
    assert.equal(sky.number, 1);
    assert.equal(items.find((i) => i.kind === "movie")!.rating, 8.3);
    const show = items.find((i) => i.kind === "series")!;
    const episodes = await loadEpisodes(fetch, login, show);
    assert.equal(episodes[0].url, "http://tv.example:8080/series/u/p/301.mp4");
    assert.equal(episodes[0].showName, "The Wire");
    await assert.rejects(
        loadPlaylist(xtreamServer(false), login, { onStep: () => {}, onItems: () => {} }),
        (e: unknown) => e instanceof LoadError && e.reason === "login",
    );
});

test("reads an M3U link as it streams in, with its guide from the header", async () => {
    const m3u =
        '#EXTM3U url-tvg="http://g.example/guide.xml"\n#EXTINF:-1 tvg-id="cbc.ca" group-title="CA| News",CBC News\nhttp://tv.example/live/1.ts\n';
    const fetch = fakeFetch((url) => (url.pathname === "/list.m3u" ? { body: m3u } : undefined), { stream: true, piece: 5 });
    const items: Item[] = [];
    const steps: Step[] = [];
    const result = await loadPlaylist(
        fetch,
        { kind: "m3u", url: "http://tv.example/list.m3u", guideUrl: "", name: "" },
        { onStep: (s) => steps.push(s), onItems: (b) => void items.push(...b) },
    );
    assert.equal(result.live, 1);
    assert.equal(result.guideUrl, "http://g.example/guide.xml");
    assert.equal(items[0].region, "CA");
    assert.ok(steps.some((s) => s.progress && s.progress.total === m3u.length));
});

test("reads a guide in pieces", () => {
    const xml =
        '<?xml version="1.0"?><tv><channel id="cbc.ca"><display-name>CBC</display-name><icon src="http://l/c.png"/></channel>' +
        '<programme start="20261127010000 +0000" stop="20261127020000 +0000" channel="cbc.ca"><title lang="en">The National &amp; More</title><desc>News.</desc><category>News</category></programme>' +
        '<programme start="20261127020000+0100" stop="20261127030000 +0100" channel="other"><title>Skip</title></programme></tv>';
    const reader = new XmltvReader((channel) => channel === "cbc.ca");
    const programmes = [];
    const channels = [];
    for (let i = 0; i < xml.length; i += 13) {
        const piece = reader.feed(xml.slice(i, i + 13));
        programmes.push(...piece.programmes);
        channels.push(...piece.channels);
    }
    programmes.push(...reader.end().programmes);
    assert.deepEqual(channels, [{ id: "cbc.ca", name: "CBC", icon: "http://l/c.png" }]);
    assert.equal(programmes.length, 1);
    assert.equal(programmes[0].title, "The National & More");
    assert.deepEqual(programmes[0].categories, ["News"]);
    assert.equal(programmes[0].start, Date.UTC(2026, 10, 27, 1));
    assert.equal(parseTime("20261127020000+0100"), Date.UTC(2026, 10, 27, 1));
    assert.equal(parseTime("nope"), null);
});

test("never has two stream connections open, however fast the channel changes", async () => {
    const events: string[] = [];
    const guard = new OneConnection({
        open: async (url) => {
            events.push(`open ${url}`);
            await new Promise((r) => setTimeout(r, 5));
        },
        close: async () => {
            events.push("close");
            await new Promise((r) => setTimeout(r, 5));
        },
    });
    await Promise.all(["a", "b", "c"].map((u) => guard.play(u)));
    await guard.stop();
    assert.equal(guard.highest, 1);
    assert.deepEqual(events, ["open a", "close", "open b", "close", "open c", "close"]);
});

test("signs the TV in to the account, takes a Premium seat, locks only when the seats are full, keeps it offline", async () => {
    let online = true;
    let seat = "active";
    const fetch = fakeFetch((url, init) => {
        if (!online) throw new Error("offline");
        const body = init.body ? JSON.parse(init.body) : {};
        if (url.pathname === "/api/tv/code") return { body: { status: "ok", code: "K7QF29MD", secret: "s3", seconds: 600 } };
        if (url.pathname === "/api/tv/take") return { body: { status: "ok", box: null, link: "L".repeat(43) } };
        if (url.pathname === "/api/auth/token") {
            assert.equal(body.kind, "tv");
            assert.equal(body.build, "tizen");
            assert.match(body.device, /^[0-9a-f]{32}$/);
            return { body: { status: "ok", token: "t0k", email: "me@example.com" } };
        }
        // The server's own answer (devices/api/account/seat.mjs): `premium` is the product's name, `seat` decides.
        if (url.pathname === "/api/account/seat") {
            assert.equal(init.headers?.Authorization, "Bearer t0k");
            assert.deepEqual(body, { use: true, purchases: [] });
            return { body: { status: "ok", email: "me@example.com", premium: "premium_yearly", seat } };
        }
        return undefined;
    });
    const account = new TunedAccount({
        fetch,
        store: new MemoryStore(),
        crypto: webCrypto,
        gunzip,
        device: { kind: "tv", build: "tizen", name: "Samsung TV" },
    });
    const code = await account.newCode();
    assert.ok(code);
    assert.equal(code.display, "K7QF-29MD");
    assert.match(code.link, /^https:\/\/www\.tunedplayer\.com\/tv#K7QF29MD\.[A-Za-z0-9_-]{87}$/);
    const taken = await account.take(code);
    assert.equal(taken.kind, "signed in");
    assert.equal((await account.state()).premium, true);
    online = false;
    assert.equal((await account.refresh()).premium, true);
    online = true;
    seat = "full";
    const full = await account.refresh();
    assert.equal(full.premium, false);
    assert.equal(full.seat, "full");
    seat = "active";
    assert.equal((await account.refresh()).premium, true);
});

test("core gives Android's answers", () => {
    assert.equal(core.kind("http://x/movie/u/p/1.mkv", "Anything"), "movie");
    assert.equal(core.regionName("UK"), "United Kingdom");
    assert.equal(core.remaining(50, 100, 1, 10_001), "s:10");
});
