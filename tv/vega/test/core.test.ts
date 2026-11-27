import { HttpError, type Http } from "../src/core/http";
import { addSecret, clearSecrets, scrub } from "../src/core/log";
import { M3uReader } from "../src/core/m3u";
import { OneStream, type StreamPlayer } from "../src/core/oneStream";
import { downloadLineup, failureText, PlaylistError } from "../src/core/playlist";
import { bytesLine, eta } from "../src/core/progress";
import { memoryStore, storage } from "../src/core/storage";
import { fromLink, liveUrl, normalizeServer, parseShortEpg } from "../src/core/xtream";

describe("M3U", () => {
    const text = [
        "#EXTM3U",
        '#EXTINF:-1 tvg-id="one.ca" tvg-logo="http://logo/1.png" group-title="News, Canada",CBC News',
        "http://tv.example/live/u/p/1.ts",
        '#EXTINF:-1 group-title="Films",A Film',
        "http://tv.example/movie/u/p/9.mkv",
        "#EXTINF:-1,No Group",
        "#EXTGRP:Sports",
        "http://tv.example/live/u/p/2.ts",
    ].join("\r\n");

    it("reads live channels in pieces, leaving out films, with commas in quoted groups", () => {
        const reader = new M3uReader();
        for (let i = 0; i < text.length; i += 7) reader.feed(text.slice(i, i + 7));
        const { categories, channels } = reader.end();
        expect(channels.map((c) => c.name)).toEqual(["CBC News", "No Group"]);
        expect(categories.map((c) => c.name)).toEqual(["News, Canada", "Sports"]);
        expect(channels[0]).toMatchObject({
            logo: "http://logo/1.png",
            guideId: "one.ca",
            number: 1,
            url: "http://tv.example/live/u/p/1.ts",
        });
        expect(reader.sawHeader).toBe(true);
    });
});

describe("Xtream", () => {
    it("builds links like core/XtreamUrls", () => {
        expect(normalizeServer(" tv.example:8080/ ")).toBe("http://tv.example:8080");
        expect(liveUrl("https://tv.example", "a b", "p&q", 12)).toBe("https://tv.example/live/a%20b/p%26q/12.ts");
        expect(fromLink("http://tv.example:8080/get.php?username=u&password=p&type=m3u_plus")).toEqual({
            kind: "xtream",
            server: "http://tv.example:8080",
            username: "u",
            password: "p",
        });
        expect(fromLink("http://tv.example/list.m3u")).toBeNull();
    });

    it("reads now and next in base64, oldest first", () => {
        const epg = parseShortEpg({
            epg_listings: [
                { title: Buffer.from("Later").toString("base64"), start_timestamp: "200", stop_timestamp: "300" },
                { title: Buffer.from("Nöw").toString("base64"), start_timestamp: "100", stop_timestamp: "200" },
                { title: "", start_timestamp: "1", stop_timestamp: "2" },
            ],
        });
        expect(epg.map((p) => p.title)).toEqual(["Nöw", "Later"]);
    });
});

describe("downloading a playlist", () => {
    const login = { kind: "xtream" as const, server: "http://tv.example", username: "u", password: "p" };
    const fake = (answers: Record<string, unknown>): Http => ({
        async getJson(url) {
            return { status: 200, body: answers[new URL(url).searchParams.get("action") ?? "account"] };
        },
        async postJson() {
            return { status: 500, body: null };
        },
        async text(url, options) {
            const body = JSON.stringify(answers[new URL(url).searchParams.get("action") ?? ""]);
            options?.onProgress?.({ loaded: body.length, total: body.length });
            return body;
        },
    });

    it("goes through numbered steps and keeps only categories with channels", async () => {
        const steps: string[] = [];
        const lineup = await downloadLineup(
            fake({
                account: { user_info: { auth: 1, status: "Active" } },
                get_live_categories: [
                    { category_id: "1", category_name: "News" },
                    { category_id: "2", category_name: "Empty" },
                ],
                get_live_streams: [{ stream_id: 5, name: "One", num: 3, category_id: "1" }],
            }),
            login,
            (p) => steps.push(`${p.step}/${p.steps}`),
        );
        expect([...new Set(steps)]).toEqual(["1/3", "2/3", "3/3"]);
        expect(lineup.categories.map((c) => c.name)).toEqual(["News"]);
        expect(lineup.channels[0]).toMatchObject({ number: 3, url: "http://tv.example/live/u/p/5.ts", streamId: 5 });
    });

    it("says a wrong login in words", async () => {
        const error = await downloadLineup(fake({ account: { user_info: { auth: 0 } } }), login, () => {}).catch((e) => e);
        expect(error).toBeInstanceOf(PlaylistError);
        expect(failureText(error)).toMatch(/didn't accept/);
        const http: Http = { ...fake({}), getJson: async () => Promise.reject(new HttpError(500)) };
        expect(failureText(await downloadLineup(http, login, () => {}).catch((e) => e))).toMatch(/Couldn't reach/);
    });
});

describe("one stream connection", () => {
    it("closes the old stream before the next opens, and the newest request wins", async () => {
        const events: string[] = [];
        let guard: OneStream;
        const player: StreamPlayer = {
            async open(url) {
                expect(guard.open).toBe(1);
                events.push(`open ${url}`);
                await new Promise((r) => setTimeout(r, 5));
            },
            async close() {
                events.push("close");
                await new Promise((r) => setTimeout(r, 5));
            },
        };
        guard = new OneStream(player);
        expect(await guard.play("a")).toBe(true);
        const second = guard.play("b");
        const third = guard.play("c");
        expect(await Promise.all([second, third])).toEqual([false, true]);
        expect(events).toEqual(["open a", "close", "open c"]);
        await guard.stop();
        expect(events).toEqual(["open a", "close", "open c", "close"]);
        expect(guard.open).toBe(0);
    });

    it("lets go of a stream that failed to open", async () => {
        const guard = new OneStream({ open: async () => Promise.reject(new Error("no")), close: async () => {} });
        expect(await guard.play("a")).toBe(false);
        expect(guard.open).toBe(0);
        expect(guard.current).toBeNull();
    });
});

describe("storage", () => {
    it("keeps a big lineup in chunks and clears everything on sign-out", async () => {
        const store = memoryStore({ other: "kept" });
        const s = storage(store);
        const channels = Array.from({ length: 4500 }, (_, i) => ({
            id: String(i),
            name: `C${i}`,
            number: i + 1,
            categoryId: "1",
            logo: null,
            url: "u",
        }));
        await s.saveLineup({ categories: [{ id: "1", name: "All" }], channels, savedAt: 5 });
        await s.saveLineup({ categories: [{ id: "1", name: "All" }], channels: channels.slice(0, 10), savedAt: 6 });
        expect((await s.loadLineup())?.channels).toHaveLength(10);
        expect((await store.keys()).filter((k) => k.startsWith("tuned:lineup:"))).toEqual(["tuned:lineup:0"]);
        await store.set("tuned:account.token", "tok");
        await s.reset();
        expect(await store.keys()).toEqual(["other"]);
    });
});

describe("progress and the log", () => {
    it("says bytes of the full size and the time left", () => {
        expect(bytesLine(4.2 * 1024 * 1024, 18 * 1024 * 1024)).toBe("4.2 MB of 18 MB");
        expect(bytesLine(300 * 1024, null)).toBe("300 KB so far");
        expect(eta(10, 100, 0, 1000)).toBeNull();
        expect(eta(10, 100, 0, 30_000)).toBe("about 5 min left");
    });

    it("keeps logins out of log lines", () => {
        addSecret("hunter2");
        expect(scrub("GET http://tv.example/live/anna/hunter2/1.ts?password=x&b=1")).toBe(
            "GET http://tv.example/live/•••/•••/1.ts?password=•••&b=1",
        );
        expect(scrub("Bearer abc.def")).toBe("Bearer •••");
        clearSecrets();
    });
});
