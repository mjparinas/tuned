import { HttpError, type Http } from "../src/core/http";
import { addSecret, clearSecrets, scrub } from "../src/core/log";
import { M3uReader } from "../src/core/m3u";
import { OneStream, type StreamPlayer } from "../src/core/oneStream";
import { downloadLibrary, downloadLineup, failureText, PlaylistError } from "../src/core/playlist";
import { bytesLine, clockTime, eta } from "../src/core/progress";
import { memoryStore, storage } from "../src/core/storage";
import { episodeName, fromLink, liveUrl, normalizeServer, parseEpisodes, parseMovies, parseShortEpg, parseShows } from "../src/core/xtream";

describe("M3U", () => {
    const text = [
        "#EXTM3U",
        '#EXTINF:-1 tvg-id="one.ca" tvg-logo="http://logo/1.png" group-title="News, Canada",CBC News',
        "http://tv.example/live/u/p/1.ts",
        '#EXTINF:-1 group-title="Movies",A Movie',
        "http://tv.example/movie/u/p/9.mkv",
        "#EXTINF:-1,No Group",
        "#EXTGRP:Sports",
        "http://tv.example/live/u/p/2.ts",
    ].join("\r\n");

    it("reads live channels in pieces, keeping movies out of Live TV, with commas in quoted groups", () => {
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

    it("sorts movies and episodes into Movies and Shows, an episode going to its show by name", () => {
        const reader = new M3uReader();
        reader.feed(
            [
                "#EXTM3U",
                '#EXTINF:-1 tvg-logo="http://logo/m.jpg" group-title="Films",Paper Boats (2025)',
                "http://tv.example/movie/u/p/9.mkv",
                '#EXTINF:-1 group-title="Drama",Harbour Street S01 E02 The Night Shift',
                "http://tv.example/series/u/p/12.mp4",
                '#EXTINF:-1 group-title="Drama",Harbour Street S01E01',
                "http://tv.example/series/u/p/11.mp4",
                "",
            ].join("\n"),
        );
        const { channels, movies, shows, movieCategories, showCategories } = reader.end();
        expect(channels).toEqual([]);
        expect(movies[0]).toMatchObject({ name: "Paper Boats (2025)", year: 2025, poster: "http://logo/m.jpg", kind: "movie" });
        expect(movieCategories.map((c) => c.name)).toEqual(["Films"]);
        expect(showCategories.map((c) => c.name)).toEqual(["Drama"]);
        expect(shows).toHaveLength(1);
        expect(shows[0].name).toBe("Harbour Street");
        expect(shows[0].episodes!.map((e) => [e.season, e.episode, e.name])).toEqual([
            [1, 1, null],
            [1, 2, "The Night Shift"],
        ]);
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

describe("Xtream movies and shows", () => {
    const login = { kind: "xtream" as const, server: "tv.example", username: "u", password: "p" };

    it("reads movies with their own file type, year and rating", () => {
        const movies = parseMovies(
            [
                {
                    stream_id: 7,
                    name: "Paper Boats",
                    category_id: 3,
                    stream_icon: "http://img/7.jpg",
                    rating: "7.8",
                    year: "2025",
                    container_extension: "mkv",
                },
                { stream_id: 8, name: "No Year (2019)", rating: 0, stream_icon: "", container_extension: "../x" },
                { stream_id: "x", name: "Broken" },
                { stream_id: 9, name: "" },
            ],
            login,
        );
        expect(movies).toEqual([
            {
                id: "m7",
                kind: "movie",
                name: "Paper Boats",
                categoryId: "3",
                poster: "http://img/7.jpg",
                year: 2025,
                rating: 7.8,
                url: "http://tv.example/movie/u/p/7.mkv",
            },
            expect.objectContaining({ id: "m8", year: 2019, rating: null, poster: null, url: "http://tv.example/movie/u/p/8.mp4" }),
        ]);
    });

    it("reads shows, and their episodes with real names over S1 E1, in order", () => {
        expect(
            parseShows([{ series_id: 4, name: "Harbour Street", cover: "http://img/4.jpg", plot: "A town.", releaseDate: "2024-02-01" }]),
        ).toEqual([expect.objectContaining({ id: "s4", kind: "show", seriesId: 4, plot: "A town.", year: 2024 })]);
        const episodes = parseEpisodes(
            {
                episodes: {
                    "2": [{ id: "21", episode_num: 1, season: 2, title: "Harbour Street - S02E01", container_extension: "mp4" }],
                    "1": [
                        {
                            id: "12",
                            episode_num: "2",
                            season: "1",
                            title: "Harbour Street - S01E02 - The Night Shift",
                            container_extension: "mkv",
                        },
                        {
                            id: "11",
                            episode_num: "1",
                            season: "1",
                            title: "Episode 1",
                            info: { name: "A Ship Comes In", plot: "It begins." },
                        },
                    ],
                },
            },
            login,
            "Harbour Street",
        );
        expect(episodes.map((e) => [e.season, e.episode, e.name])).toEqual([
            [1, 1, "A Ship Comes In"],
            [1, 2, "The Night Shift"],
            [2, 1, null],
        ]);
        expect(episodes[1].url).toBe("http://tv.example/series/u/p/12.mkv");
        expect(episodes[0].plot).toBe("It begins.");
        expect(episodeName("S01E03", "Show")).toBeNull();
        expect(clockTime(3723)).toBe("1:02:03");
        expect(clockTime(754)).toBe("12:34");
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

    it("downloads movies and shows in four numbered steps, keeping only categories in use", async () => {
        const steps: string[] = [];
        const library = await downloadLibrary(
            fake({
                get_vod_categories: [
                    { category_id: "1", category_name: "Action" },
                    { category_id: "2", category_name: "Empty" },
                ],
                get_vod_streams: [{ stream_id: 5, name: "Fast Water", category_id: "1" }],
                get_series_categories: [{ category_id: "9", category_name: "Drama" }],
                get_series: [{ series_id: 3, name: "Harbour Street", category_id: "9" }],
            }),
            login,
            (p) => steps.push(`${p.step}/${p.steps} ${p.label}`),
        );
        expect([...new Set(steps)]).toEqual([
            "1/4 Downloading movie categories",
            "2/4 Downloading movies",
            "3/4 Downloading show categories",
            "4/4 Downloading shows",
        ]);
        expect(library.movieCategories.map((c) => c.name)).toEqual(["Action"]);
        expect(library.movies[0].url).toBe("http://tv.example/movie/u/p/5.mp4");
        expect(library.shows[0]).toMatchObject({ name: "Harbour Street", seriesId: 3 });
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

    it("keeps movies and shows in chunks beside the lineup, and where each was left, newest kept", async () => {
        const store = memoryStore();
        const s = storage(store);
        const movies = Array.from({ length: 4100 }, (_, i) => ({
            id: `m${i}`,
            kind: "movie" as const,
            name: `M${i}`,
            categoryId: "1",
            poster: null,
            year: null,
            rating: null,
            url: "u",
        }));
        const base = { movieCategories: [{ id: "1", name: "All" }], showCategories: [], shows: [] };
        await s.saveLibrary({ ...base, movies, savedAt: 1 });
        await s.saveLibrary({ ...base, movies: movies.slice(0, 2001), savedAt: 2 });
        expect((await s.loadLibrary())?.movies).toHaveLength(2001);
        expect((await store.keys()).filter((k) => k.startsWith("tuned:library:")).sort()).toEqual([
            "tuned:library:m:0",
            "tuned:library:m:1",
        ]);
        for (let i = 0; i < 305; i++) await s.savePosition(`e${i}`, 60);
        const kept = await s.savePosition("e0", 90);
        expect(Object.keys(kept)).toHaveLength(300);
        expect(kept.e0).toBe(90);
        expect(kept.e5).toBeUndefined();
        expect((await s.savePosition("e0", 0)).e0).toBeUndefined();
        await s.reset();
        expect(await store.keys()).toEqual([]);
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
