import type { Fetch } from "@tuned/shared/platform";
import { pbkdf2Sync } from "node:crypto";
import { fnv64 } from "@tuned/shared/stableid";
import { AppModel, CONTINUE, FAVOURITES, hasPremium } from "../src/app/model";
import type { Http } from "../src/core/http";
import { memoryStore } from "../src/core/storage";

function http(routes: Record<string, (body: any) => { status: number; body: any } | Promise<never>>): Http {
    const answer = async (url: string, body?: any) => {
        const path = new URL(url).pathname;
        const route = routes[path];
        if (!route) throw new Error(`no route ${path}`);
        return route(body);
    };
    return {
        getJson: (url) => answer(url),
        postJson: (url, body) => answer(url, body),
        text: async () => {
            throw new Error("no text");
        },
    };
}

/** The same routes as `fetch`, which the account and relay calls use. */
const fetchFrom =
    (h: Http): Fetch =>
    async (url, init) => {
        const answer = init?.method === "POST" ? await h.postJson(url, JSON.parse(init.body ?? "{}")) : await h.getJson(url);
        return {
            ok: answer.status >= 200 && answer.status < 300,
            status: answer.status,
            headers: { get: () => null },
            text: async () => JSON.stringify(answer.body),
            json: async () => answer.body,
        };
    };

const services = (h: Http, store = memoryStore()) => ({
    store,
    http: h,
    fetch: fetchFrom(h),
    random: (n: number) => new Uint8Array(n).fill(7),
    player: { open: async () => {}, close: async () => {} },
    deviceName: "Test stick",
    site: "https://tuned.example",
});

describe("the Tuned account on Vega", () => {
    it("signs in with the website's link as a vega TV and takes a Premium seat", async () => {
        const asked: any[] = [];
        const model = new AppModel(
            services(
                http({
                    "/api/auth/token": (b) => (
                        asked.push(b),
                        { status: 200, body: { status: "ok", token: "tok", email: "a@example.com" } }
                    ),
                    "/api/account/seat": () => ({ status: 200, body: { seat: "active" } }),
                    "/api/backup": () => ({ status: 404, body: { status: "none" } }),
                }),
            ),
        );
        expect(await model.accountLinked("link")).toBe(true);
        expect(asked[0]).toMatchObject({ link: "link", kind: "tv", build: "vega", name: "Test stick", device: "07".repeat(16) });
        expect(model.state.account).toMatchObject({ email: "a@example.com", premium: true, seat: "active" });
        expect(hasPremium(model.state.account)).toBe(true);
    });

    it("keeps Premium through an outage, and only full locks", async () => {
        let up = true;
        let seat = "active";
        const model = new AppModel(
            services(
                http({
                    "/api/auth/token": () => ({ status: 200, body: { status: "ok", token: "tok", email: "" } }),
                    "/api/account/seat": () => (up ? { status: 200, body: { seat } } : Promise.reject(new Error("offline"))),
                    "/api/backup": () => ({ status: 404, body: null }),
                }),
            ),
        );
        await model.accountLinked("link");
        up = false;
        await model.checkAccount();
        expect(hasPremium(model.state.account)).toBe(true);
        expect(model.state.accountCheck).toMatchObject({ busy: false, result: { ok: false } });
        up = true;
        seat = "full";
        await model.checkAccount();
        expect(hasPremium(model.state.account)).toBe(false);
    });

    it("restores the playlist from the account's backup on the welcome screen", async () => {
        const model = new AppModel(
            services(
                http({
                    "/api/auth/token": () => ({ status: 200, body: { status: "ok", token: "tok", email: "" } }),
                    "/api/account/seat": () => ({ status: 200, body: { seat: "none" } }),
                    "/api/backup": () => ({
                        status: 200,
                        body: { version: 3, playlists: [{ server: "http://tv.example", username: "u", password: "p", playlistUrl: "" }] },
                    }),
                    "/player_api.php": () => ({ status: 200, body: { user_info: { auth: 0 } } }),
                }),
            ),
        );
        await model.accountLinked("link");
        expect(model.state.login).toMatchObject({ kind: "xtream", server: "http://tv.example" });
        expect(model.state.route).toBe("loading");
        expect(model.state.loading.error).toMatch(/didn't accept/);
    });

    it("signs out back to how it was installed", async () => {
        const store = memoryStore();
        const model = new AppModel(
            services(
                http({
                    "/api/auth/token": () => ({ status: 200, body: { status: "ok", token: "tok", email: "" } }),
                    "/api/account/seat": () => ({ status: 200, body: { seat: "active" } }),
                    "/api/backup": () => ({ status: 404, body: null }),
                    "/api/auth/signout": () => ({ status: 200, body: { status: "ok" } }),
                }),
                store,
            ),
        );
        await model.accountLinked("link");
        await model.saved.saveLogin({ kind: "link", playlistUrl: "http://x.example/a.m3u" });
        await model.signOut();
        expect(await store.keys()).toEqual([]);
        expect(model.state).toMatchObject({ route: "signin", login: null, account: null });
    });
});

describe("Movies and Shows on Vega", () => {
    const login = { kind: "xtream" as const, server: "http://tv.example", username: "u", password: "p" };
    const answers: Record<string, unknown> = {
        account: { user_info: { auth: 1, status: "Active" } },
        get_live_categories: [{ category_id: "1", category_name: "News" }],
        get_live_streams: [{ stream_id: 5, name: "One", category_id: "1" }],
        get_vod_categories: [{ category_id: "2", category_name: "Action" }],
        get_vod_streams: [{ stream_id: 7, name: "Fast Water", category_id: "2", container_extension: "mkv" }],
        get_series_categories: [{ category_id: "3", category_name: "Drama" }],
        get_series: [{ series_id: 4, name: "Harbour Street", category_id: "3" }],
        get_series_info: {
            episodes: { "1": [{ id: "41", season: 1, episode_num: 1, title: "Harbour Street - S01E01 - A Ship Comes In" }] },
        },
    };
    const xtream = (asked: string[] = []): Http => ({
        async getJson(url) {
            const action = new URL(url).searchParams.get("action") ?? "account";
            asked.push(action);
            return { status: 200, body: answers[action] };
        },
        async postJson() {
            return { status: 500, body: null };
        },
        async text(url) {
            const action = new URL(url).searchParams.get("action") ?? "";
            asked.push(action);
            return JSON.stringify(answers[action]);
        },
    });

    it("shows Live TV first, then gets movies and shows in the background and keeps them", async () => {
        const asked: string[] = [];
        const store = memoryStore();
        const model = new AppModel({ ...services(xtream(asked), store), player: { open: async () => {}, close: async () => {} } });
        await model.useLogin(login);
        expect(model.state.route).toBe("live");
        await new Promise((r) => setTimeout(r, 0));
        await until(() => !!model.state.library);
        expect(asked.indexOf("get_live_streams")).toBeLessThan(asked.indexOf("get_vod_streams"));
        expect(model.state.library!.movies[0]).toMatchObject({ name: "Fast Water", url: "http://tv.example/movie/u/p/7.mkv" });
        expect(model.state.libraryLoad.result).toMatchObject({ ok: true });

        const again = new AppModel({ ...services(xtream(), store), player: { open: async () => {}, close: async () => {} } });
        await again.boot();
        await again.openBrowse("shows");
        expect(again.state.route).toBe("shows");
        expect(again.visibleTitles("shows").map((t) => t.name)).toEqual(["Harbour Street"]);
    });

    it("opens a show with its episodes' real names and plays one, one connection, picking up where it was left", async () => {
        const open: (string | number | undefined)[][] = [];
        let at = 0;
        const player = {
            open: async (url: string, startAt?: number) => void open.push([url, startAt]),
            close: async () => {},
            position: () => ({ at, length: 2400 }),
        };
        const model = new AppModel({ ...services(xtream()), player });
        await model.useLogin(login);
        await until(() => !!model.state.library);
        const show = model.visibleTitles("shows")[0];
        await model.openTitle(show);
        const [episode] = model.episodesOf(show)!;
        expect(episode).toMatchObject({ name: "A Ship Comes In", url: "http://tv.example/series/u/p/41.mp4" });

        await model.playVod({ id: episode.id, title: show.name, url: episode.url });
        at = 600;
        await model.stopPlaying();
        expect(model.state.route).toBe("title");
        expect(model.state.positions[episode.id]).toBe(600);
        await model.playVod({ id: episode.id, title: show.name, url: episode.url }, model.state.positions[episode.id]);
        expect(open).toEqual([
            ["http://tv.example/series/u/p/41.mp4", undefined],
            ["http://tv.example/series/u/p/41.mp4", 600],
        ]);
        expect(model.stream.open).toBe(1);
        at = 2390;
        await model.stopPlaying();
        expect(model.state.positions[episode.id]).toBeUndefined();
    });

    it("says when a show's episodes couldn't be read, with Try again", async () => {
        const broken = xtream();
        const model = new AppModel({ ...services(broken), player: { open: async () => {}, close: async () => {} } });
        await model.useLogin(login);
        await until(() => !!model.state.library);
        broken.getJson = async () => {
            throw new Error("offline");
        };
        await model.openTitle(model.visibleTitles("shows")[0]);
        expect(model.state.episodesLoad).toMatchObject({ busy: false, failed: true });
    });
});

async function until(check: () => boolean): Promise<void> {
    for (let i = 0; i < 100 && !check(); i++) await new Promise((r) => setTimeout(r, 0));
    if (!check()) throw new Error("never happened");
}

describe("search, favourites and TMDB details on Vega", () => {
    const login = { kind: "xtream" as const, server: "http://tv.example", username: "u", password: "p" };
    const answers: Record<string, unknown> = {
        account: { user_info: { auth: 1, status: "Active" } },
        get_live_categories: [{ category_id: "1", category_name: "News" }],
        get_live_streams: [
            { stream_id: 5, name: "Télé-Québec", category_id: "1" },
            { stream_id: 6, name: "Harbour News", category_id: "1" },
        ],
        get_vod_categories: [{ category_id: "2", category_name: "Drama" }],
        get_vod_streams: [{ stream_id: 7, name: "EN | Paper Boats (2025)", category_id: "2" }],
        get_series_categories: [{ category_id: "3", category_name: "Drama" }],
        get_series: [{ series_id: 4, name: "Harbour Street", category_id: "3" }],
        get_series_info: { episodes: { "1": [{ id: "41", season: 1, episode_num: 1, title: "Harbour Street S01E01" }] } },
    };
    const xtream: Http = {
        async getJson(url) {
            return { status: 200, body: answers[new URL(url).searchParams.get("action") ?? "account"] };
        },
        async postJson() {
            return { status: 500, body: null };
        },
        async text(url) {
            return JSON.stringify(answers[new URL(url).searchParams.get("action") ?? ""]);
        },
    };
    const tmdbAsked: string[] = [];
    let tmdbUp = true;
    const tmdbFetch: Fetch = async (url) => {
        const u = new URL(url);
        tmdbAsked.push(u.pathname);
        const body: unknown = !tmdbUp
            ? null
            : u.pathname === "/3/search/movie"
              ? { results: [{ id: 9 }] }
              : u.pathname === "/3/movie/9"
                ? { id: 9, title: "Paper Boats", overview: "Two friends.", runtime: 112, genres: [{ name: "Drama" }], vote_average: 7.8 }
                : u.pathname === "/3/search/tv"
                  ? { results: [{ id: 44 }] }
                  : u.pathname === "/3/tv/44"
                    ? { id: 44, name: "Harbour Street" }
                    : u.pathname === "/3/tv/44/season/1"
                      ? { episodes: [{ episode_number: 1, name: "A Ship Comes In" }] }
                      : null;
        return {
            ok: tmdbUp && body !== null,
            status: tmdbUp ? 200 : 503,
            headers: { get: () => null },
            text: async () => JSON.stringify(body),
            json: async () => body,
        };
    };
    const make = (store = memoryStore()) =>
        new AppModel({
            ...services(xtream, store),
            fetch: tmdbFetch,
            tmdbKey: "k",
            player: { open: async () => {}, close: async () => {} },
        });

    it("finds channels, movies and shows without accents or punctuation, channels first", async () => {
        const model = make();
        await model.useLogin(login);
        await until(() => !!model.state.library);
        model.setQuery("tele que");
        expect(model.results().map((f) => (f.kind === "live" ? f.channel.name : f.title.name))).toEqual(["Télé-Québec"]);
        model.setQuery("harbour");
        expect(model.results().map((f) => f.kind)).toEqual(["live", "show"]);
        model.setQuery("  ");
        expect(model.results()).toEqual([]);
    });

    it("keeps favourites across a restart, shows them as their own category, and says what a star did", async () => {
        const store = memoryStore();
        const model = make(store);
        await model.useLogin(login);
        await until(() => !!model.state.library);
        await model.toggleFavourite("6", "Harbour News");
        expect(model.state.notice).toEqual({ on: true, name: "Harbour News" });
        await model.toggleFavourite("m7", "Paper Boats");
        model.pickCategory(FAVOURITES);
        expect(model.visibleChannels().map((c) => c.name)).toEqual(["Harbour News"]);

        const again = make(store);
        await again.boot();
        expect([...again.state.favourites].sort()).toEqual(["6", "m7"]);
        await again.openBrowse("movies");
        again.pickBrowseCategory("movies", FAVOURITES);
        expect(again.visibleTitles("movies").map((x) => x.id)).toEqual(["m7"]);
        await again.signOut();
        expect(again.state.favourites.size).toBe(0);
    });

    it("fills a page from TMDB, with real episode names, and keeps the playlist's own when TMDB is down", async () => {
        const model = make();
        await model.useLogin(login);
        await until(() => !!model.state.library);
        const movie = model.visibleTitles("movies")[0];
        await model.openTitle(movie);
        await until(() => !model.state.detailsLoad.busy && movie.id in model.state.details);
        expect(model.state.details[movie.id]).toMatchObject({ overview: "Two friends.", runtime: 112, genres: ["Drama"] });

        const show = model.visibleTitles("shows")[0];
        await model.openTitle(show);
        await until(() => Object.keys(model.state.seasons).length > 0);
        const [episode] = model.episodesOf(show)!;
        expect(episode.name).toBeNull();
        expect(model.episodeName(show, episode)).toBe("A Ship Comes In");

        // A stick that can't reach TMDB says so and keeps the playlist's own details.
        const asked = tmdbAsked.length;
        const fresh = make();
        await fresh.useLogin(login);
        tmdbUp = false;
        await until(() => !!fresh.state.library);
        await fresh.openTitle(fresh.visibleTitles("movies")[0]);
        await until(() => !fresh.state.detailsLoad.busy && fresh.state.detailsLoad.id !== null);
        expect(fresh.state.detailsLoad.failed).toBe(true);
        expect(tmdbAsked.length).toBeGreaterThan(asked);
        tmdbUp = true;
    });
});

describe("sorting, hiding adult and 24/7, and Continue Watching on Vega", () => {
    const login = { kind: "xtream" as const, server: "http://tv.example", username: "u", password: "p" };
    const answers: Record<string, unknown> = {
        account: { user_info: { auth: 1, status: "Active" } },
        get_live_categories: [
            { category_id: "1", category_name: "News" },
            { category_id: "9", category_name: "XXX | Adults" },
        ],
        get_live_streams: [
            { stream_id: 5, name: "Harbour News", category_id: "1" },
            { stream_id: 6, name: "24/7 Harbour Street", category_id: "1" },
            { stream_id: 8, name: "Late", category_id: "9" },
        ],
        get_vod_categories: [{ category_id: "2", category_name: "Drama" }],
        get_vod_streams: [
            { stream_id: 7, name: "Paper Boats", category_id: "2", year: "2019", rating: "6", added: "1700000300" },
            { stream_id: 10, name: "A Quiet Bay", category_id: "2", year: "2024", rating: "8.5", added: "1700000100" },
            { stream_id: 11, name: "Lighthouse", category_id: "2", year: "2021", rating: "0", added: "1700000200" },
        ],
        get_series_categories: [{ category_id: "3", category_name: "Drama" }],
        get_series: [{ series_id: 4, name: "Harbour Street", category_id: "3", last_modified: "1700000000" }],
        get_series_info: { episodes: { "1": [{ id: "41", season: 1, episode_num: 1, title: "Harbour Street S01E01" }] } },
    };
    const xtream: Http = {
        async getJson(url) {
            return { status: 200, body: answers[new URL(url).searchParams.get("action") ?? "account"] };
        },
        async postJson() {
            return { status: 500, body: null };
        },
        async text(url) {
            return JSON.stringify(answers[new URL(url).searchParams.get("action") ?? ""]);
        },
    };

    it("hides adult categories by default and 24/7 channels when asked, everywhere, and keeps the choice", async () => {
        const store = memoryStore();
        const model = new AppModel(services(xtream, store));
        await model.boot();
        await model.useLogin(login);
        await until(() => !!model.state.library);
        expect(model.state.prefs).toMatchObject({ hideAdult: true, hide247: false });
        expect(model.shownCategories("live").map((c) => c.name)).toEqual(["News"]);
        expect(model.visibleChannels().map((c) => c.name)).toEqual(["Harbour News", "24/7 Harbour Street"]);

        await model.setPrefs({ hide247: true });
        expect(model.visibleChannels().map((c) => c.name)).toEqual(["Harbour News"]);
        model.setQuery("harbour");
        expect(model.results().map((r) => (r.kind === "live" ? r.channel.name : r.title.name))).toEqual(["Harbour News", "Harbour Street"]);

        await model.setPrefs({ hideAdult: false, hide247: false });
        model.pickCategory("9");
        expect(model.visibleChannels().map((c) => c.name)).toEqual(["Late"]);
        await model.setPrefs({ hideAdult: true });
        expect(model.state.category).toBeNull();

        const again = new AppModel(services(xtream, store));
        await again.boot();
        expect(again.state.prefs).toMatchObject({ hideAdult: true, hide247: false });
    });

    it("sorts each tab its own way and keeps it", async () => {
        const store = memoryStore();
        const model = new AppModel(services(xtream, store));
        await model.useLogin(login);
        await until(() => !!model.state.library);
        const names = () => model.visibleTitles("movies").map((x) => x.name);
        expect(names()).toEqual(["Paper Boats", "A Quiet Bay", "Lighthouse"]);
        await model.nextSort("movies");
        expect(model.state.prefs.sortMovies).toBe("added");
        expect(names()).toEqual(["Paper Boats", "Lighthouse", "A Quiet Bay"]);
        await model.setPrefs({ sortMovies: "year" });
        expect(names()).toEqual(["A Quiet Bay", "Lighthouse", "Paper Boats"]);
        await model.setPrefs({ sortMovies: "rating" });
        expect(names()).toEqual(["A Quiet Bay", "Paper Boats", "Lighthouse"]);
        await model.setPrefs({ sortMovies: "title" });
        expect(names()).toEqual(["Lighthouse", "Paper Boats", "A Quiet Bay"]);
        expect(model.state.prefs.sortShows).toBe("provider");

        const again = new AppModel(services(xtream, store));
        await again.boot();
        expect(again.state.prefs.sortMovies).toBe("title");
    });

    it("puts a half-watched movie or episode in Continue watching, and takes it out once finished", async () => {
        let at = 0;
        const player = { open: async () => {}, close: async () => {}, position: () => ({ at, length: 2400 }) };
        const store = memoryStore();
        const model = new AppModel({ ...services(xtream, store), player });
        await model.useLogin(login);
        await until(() => !!model.state.library);
        const show = model.visibleTitles("shows")[0];
        await model.openTitle(show);
        const [episode] = model.episodesOf(show)!;
        await model.playVod({ id: episode.id, titleId: show.id, title: show.name, url: episode.url });
        at = 600;
        await model.stopPlaying();
        expect(model.continueTitles("shows").map((x) => x.name)).toEqual(["Harbour Street"]);
        model.pickBrowseCategory("shows", CONTINUE);
        expect(model.visibleTitles("shows").map((x) => x.name)).toEqual(["Harbour Street"]);

        const again = new AppModel({ ...services(xtream, store), player });
        await again.boot();
        await again.openBrowse("shows");
        expect(again.state.positions[episode.id]).toBe(600);
        expect(again.continueTitles("shows").map((x) => x.name)).toEqual(["Harbour Street"]);

        await model.playVod({ id: episode.id, titleId: show.id, title: show.name, url: episode.url }, 600);
        at = 2390;
        await model.stopPlaying();
        expect(model.continueTitles("shows")).toEqual([]);
        expect(model.state.positions[episode.id]).toBeUndefined();
    });

    it("carries over where movies were left before Continue watching", async () => {
        const store = memoryStore();
        await store.set("tuned:positions", JSON.stringify({ m10: 900, e41: 300 }));
        const model = new AppModel(services(xtream, store));
        await model.useLogin(login);
        await until(() => !!model.state.library);
        await model.openBrowse("movies");
        expect(model.continueTitles("movies").map((x) => x.name)).toEqual(["A Quiet Bay"]);
    });
});

describe("more than one playlist on Vega", () => {
    const one = { kind: "xtream" as const, server: "http://one.example", username: "u", password: "p" };
    const two = { kind: "link" as const, playlistUrl: "http://two.example/list.m3u", name: "Two" };
    const m3u = '#EXTM3U\n#EXTINF:-1 group-title="Kids",Two Kids\nhttp://two.example/live/1.ts\n';
    const answers: Record<string, unknown> = {
        account: { user_info: { auth: 1, status: "Active" } },
        get_live_categories: [{ category_id: "1", category_name: "News" }],
        get_live_streams: [{ stream_id: 5, name: "One News", category_id: "1" }],
        get_vod_categories: [],
        get_vod_streams: [],
        get_series_categories: [],
        get_series: [],
    };
    const both = (premium: boolean): Http => ({
        async getJson(url) {
            const u = new URL(url);
            if (u.pathname === "/api/account/seat") return { status: 200, body: { seat: premium ? "active" : "none" } };
            if (u.pathname === "/api/auth/token") return { status: 200, body: { status: "ok", token: "tok", email: "" } };
            return { status: 200, body: answers[u.searchParams.get("action") ?? "account"] };
        },
        async postJson(url) {
            const u = new URL(url);
            if (u.pathname === "/api/auth/token") return { status: 200, body: { status: "ok", token: "tok", email: "" } };
            if (u.pathname === "/api/account/seat") return { status: 200, body: { seat: premium ? "active" : "none" } };
            return { status: 500, body: null };
        },
        async text(url) {
            if (url.startsWith("http://two.example")) return m3u;
            return JSON.stringify(answers[new URL(url).searchParams.get("action") ?? ""]);
        },
    });

    it("adds a second playlist with Premium, switches between them with each one's favourites, and removes one", async () => {
        const store = memoryStore();
        const model = new AppModel(services(both(true), store));
        await model.boot();
        await model.useLogin(one);
        await model.toggleFavourite("5", "One News");
        await model.accountLinked("link");
        expect(hasPremium(model.state.account)).toBe(true);

        model.startAdding();
        expect(model.state).toMatchObject({ route: "signin", adding: true });
        await model.useLogin(two);
        expect(model.state.route).toBe("live");
        expect(model.state.adding).toBe(false);
        expect(model.state.playlists.map((p) => p.name)).toEqual(["one.example", "Two"]);
        expect(model.visibleChannels().map((c) => c.name)).toEqual(["Two Kids"]);
        expect(model.state.favourites.size).toBe(0);

        await model.switchPlaylist(model.state.playlists[0].id);
        expect(model.state.playlistId).toBe("current");
        expect(model.visibleChannels().map((c) => c.name)).toEqual(["One News"]);
        expect([...model.state.favourites]).toEqual(["5"]);

        // A restart opens the one last used, with every playlist still there.
        const again = new AppModel(services(both(true), store));
        await again.boot();
        expect(again.state.playlists).toHaveLength(2);
        expect(again.state.playlistId).toBe("current");
        expect(again.state.lineup?.channels.map((c) => c.name)).toEqual(["One News"]);

        const second = model.state.playlists[1].id;
        await model.removePlaylist("current");
        expect(model.state.playlists).toHaveLength(2);
        await model.removePlaylist(second);
        expect(model.state.playlists.map((p) => p.name)).toEqual(["one.example"]);
    });

    it("keeps only the playlist picked without Premium, and the same login reopens rather than adding", async () => {
        const model = new AppModel(services(both(false)));
        await model.boot();
        await model.useLogin(one, [two]);
        expect(model.state.playlists).toHaveLength(1);
        await model.useLogin({ ...one, server: "http://one.example/" });
        expect(model.state.playlists).toHaveLength(1);
    });

    it("never lets an old playlist's download land on the next one", async () => {
        let release: () => void = () => {};
        const slow = both(true);
        const getJson = slow.getJson;
        let hold = true;
        slow.getJson = async (url) => {
            if (hold && url.includes("get_live_streams")) await new Promise<void>((r) => (release = r));
            return getJson(url);
        };
        const store = memoryStore();
        const model = new AppModel(services(slow, store));
        await model.boot();
        const first = model.useLogin(one);
        await new Promise((r) => setTimeout(r, 5));
        hold = false;
        await model.useLogin(two);
        release();
        await first;
        expect(model.state.lineup?.channels.map((c) => c.name)).toEqual(["Two Kids"]);
        expect(model.state.playlistId).not.toBe("current");
    });
});

describe("the guide grid, catch-up and reminders on Vega", () => {
    const login = { kind: "xtream" as const, server: "http://tv.example", username: "u", password: "p" };
    const b64 = (s: string) => Buffer.from(s).toString("base64");
    const now = Date.now();
    const sec = (ms: number) => String(Math.floor(ms / 1000));
    const hour = 3600_000;
    const asked: string[] = [];
    const answers: Record<string, unknown> = {
        account: { user_info: { auth: 1, status: "Active" }, server_info: { timezone: "UTC" } },
        get_live_categories: [{ category_id: "1", category_name: "News" }],
        get_live_streams: [
            { stream_id: 5, name: "Harbour News", category_id: "1", tv_archive: 1, tv_archive_duration: 2 },
            { stream_id: 6, name: "Quiet Channel", category_id: "1" },
        ],
        get_vod_categories: [],
        get_vod_streams: [],
        get_series_categories: [],
        get_series: [],
    };
    const table = {
        epg_listings: [
            { title: b64("Morning"), start_timestamp: sec(now - 3 * hour), stop_timestamp: sec(now - 2 * hour) },
            { title: b64("Now"), start_timestamp: sec(now - hour / 2), stop_timestamp: sec(now + hour / 2) },
            { title: b64("Later"), start_timestamp: sec(now + hour), stop_timestamp: sec(now + 2 * hour) },
            { title: b64("Next week"), start_timestamp: sec(now + 7 * 24 * hour), stop_timestamp: sec(now + 7 * 24 * hour + hour) },
        ],
    };
    const xtream: Http = {
        async getJson(url) {
            const u = new URL(url);
            const action = u.searchParams.get("action") ?? "account";
            asked.push(action === "get_simple_data_table" ? `table:${u.searchParams.get("stream_id")}` : action);
            if (action === "get_simple_data_table")
                return { status: 200, body: u.searchParams.get("stream_id") === "5" ? table : { epg_listings: [] } };
            return { status: 200, body: answers[action] };
        },
        async postJson() {
            return { status: 500, body: null };
        },
        async text(url) {
            return JSON.stringify(answers[new URL(url).searchParams.get("action") ?? ""]);
        },
    };

    it("reads the guide for the rows on screen only, once, and keeps the window the grid shows", async () => {
        const model = new AppModel(services(xtream));
        await model.useLogin(login);
        expect(model.state.lineup?.timezone).toBe("UTC");
        const [news, quiet] = model.visibleChannels();
        asked.length = 0;
        await model.loadGridRows([news]);
        await model.loadGridRows([news, quiet]);
        expect(asked).toEqual(["table:5", "table:6"]);
        expect(model.state.grid[news.id].map((p) => p.title)).toEqual(["Morning", "Now", "Later"]);
        expect(model.state.grid[quiet.id]).toEqual([]);
        await model.setPrefs({ liveGrid: true });
        expect(model.state.prefs.liveGrid).toBe(true);
    });

    it("plays a past programme from its recording through the one connection, and keeps no position for it", async () => {
        const opened: string[] = [];
        const player = {
            open: async (url: string) => void opened.push(url),
            close: async () => {},
            position: () => ({ at: 600, length: 3600 }),
        };
        const model = new AppModel({ ...services(xtream), player });
        await model.useLogin(login);
        const [news, quiet] = model.visibleChannels();
        await model.loadGridRows([news, quiet]);
        const morning = model.state.grid[news.id][0];
        expect(await model.playReplay(quiet, morning)).toBe(false);
        expect(await model.playReplay(news, morning)).toBe(true);
        expect(opened[0]).toMatch(/^http:\/\/tv\.example\/timeshift\/u\/p\/60\/\d{4}-\d\d-\d\d:\d\d-\d\d\/5\.ts$/);
        expect(model.state.vod).toMatchObject({ title: "Morning", subtitle: "Harbour News", replay: true });
        expect(model.stream.open).toBe(1);
        await model.stopPlaying();
        expect(model.state.route).toBe("live");
        expect(model.state.positions).toEqual({});
    });

    it("sets a reminder for a programme to come, tells once when it starts, and Watch plays its channel", async () => {
        jest.useFakeTimers({ now });
        try {
            const played: string[] = [];
            const player = { open: async (url: string) => void played.push(url), close: async () => {} };
            const store = memoryStore();
            const model = new AppModel({ ...services(xtream, store), player });
            await model.useLogin(login);
            const [news] = model.visibleChannels();
            await model.loadGridRows([news]);
            const later = model.state.grid[news.id][2];
            await model.toggleReminder(news, later);
            expect(model.state.reminders).toHaveLength(1);
            expect(model.state.notice?.text).toContain("Later");

            // It's kept across a restart.
            const again = new AppModel({ ...services(xtream, store), player });
            await again.boot();
            expect(again.state.reminders.map((r) => r.title)).toEqual(["Later"]);

            await jest.advanceTimersByTimeAsync(hour + 1000);
            expect(model.state.due).toMatchObject({ title: "Later", channelName: "Harbour News" });
            await model.watchDue();
            expect(model.state.due).toBeNull();
            expect(model.state.route).toBe("player");
            expect(played.at(-1)).toBe(news.url);
            expect(model.state.reminders).toHaveLength(0);
            again.dismissDue();
        } finally {
            jest.useRealTimers();
        }
    });

    it("reads a link's guide once, with progress, keeping only its channels, and says when it fails", async () => {
        const t0 = new Date(now - 30 * 60_000);
        const t1 = new Date(now + 30 * 60_000);
        const stamp = (d: Date) => d.toISOString().replace(/[-:T]/g, "").slice(0, 14) + " +0000";
        let guideUp = true;
        const xml =
            '<?xml version="1.0"?><tv><channel id="news"><display-name>News</display-name></channel>' +
            `<programme channel="news" start="${stamp(t0)}" stop="${stamp(t1)}"><title>Evening News</title><desc>Today.</desc></programme>` +
            `<programme channel="other" start="${stamp(t0)}" stop="${stamp(t1)}"><title>Not ours</title></programme></tv>`;
        const m3u = '#EXTM3U url-tvg="http://guide.example/epg.xml"\n#EXTINF:-1 tvg-id="news",News\nhttp://tv.example/live/1.ts\n';
        const progress: number[] = [];
        const link: Http = {
            async getJson() {
                return { status: 500, body: null };
            },
            async postJson() {
                return { status: 500, body: null };
            },
            async text(url, options) {
                if (url.startsWith("http://guide.example")) {
                    if (!guideUp) throw new Error("offline");
                    options?.onProgress?.({ loaded: xml.length, total: xml.length });
                    return xml;
                }
                return m3u;
            },
        };
        const model = new AppModel(services(link));
        await model.useLogin({ kind: "link", playlistUrl: "http://tv.example/list.m3u" });
        model.subscribe(() => model.state.gridLoad.progress?.loaded && progress.push(model.state.gridLoad.progress.loaded));
        const [news] = model.visibleChannels();
        await model.loadGridRows([news]);
        expect(model.state.grid[news.id]).toEqual([
            {
                start: t0.getTime() - (t0.getTime() % 1000),
                stop: t1.getTime() - (t1.getTime() % 1000),
                title: "Evening News",
                desc: "Today.",
            },
        ]);
        expect(progress.length).toBeGreaterThan(0);
        expect(model.state.gridLoad).toMatchObject({ busy: false, result: { ok: true } });

        guideUp = false;
        await model.loadLinkGuide(true);
        expect(model.state.gridLoad).toMatchObject({ busy: false, result: { ok: false } });
    });
});

describe("live events and auto-tune on Vega", () => {
    const login = { kind: "xtream" as const, server: "http://tv.example", username: "u", password: "p" };
    const b64 = (s: string) => Buffer.from(s).toString("base64");
    const now = Date.now();
    const sec = (ms: number) => String(Math.floor(ms / 1000));
    const hour = 3600_000;
    const asked: string[] = [];
    const answers: Record<string, unknown> = {
        account: { user_info: { auth: 1, status: "Active" }, server_info: { timezone: "UTC" } },
        get_live_categories: [
            { category_id: "1", category_name: "Sports" },
            { category_id: "2", category_name: "PPV" },
            { category_id: "3", category_name: "News" },
        ],
        get_live_streams: [
            { stream_id: 10, name: "US| ESPN", category_id: "1" },
            { stream_id: 11, name: "CA| TSN 1", category_id: "1" },
            { stream_id: 12, name: "PPV 1: UFC 312", category_id: "2" },
            { stream_id: 13, name: "ES| PPV 2: UFC 312", category_id: "2" },
            { stream_id: 14, name: "CNN", category_id: "3" },
        ],
        get_vod_categories: [],
        get_vod_streams: [],
        get_series_categories: [],
        get_series: [],
    };
    const listing = (title: string, from: number, to: number) => ({
        title: b64(title),
        start_timestamp: sec(from),
        stop_timestamp: sec(to),
    });
    const tables: Record<string, unknown> = {
        "10": {
            epg_listings: [
                listing("Leafs vs. Canucks", now - hour, now + 2 * hour),
                listing("Raptors vs. Celtics", now + 3 * hour, now + 5 * hour),
            ],
        },
        "11": { epg_listings: [listing("LIVE: Leafs vs. Canucks", now - hour, now + 2 * hour)] },
    };
    const xtream: Http = {
        async getJson(url) {
            const u = new URL(url);
            const action = u.searchParams.get("action") ?? "account";
            if (action === "get_simple_data_table") {
                const id = u.searchParams.get("stream_id") ?? "";
                asked.push(`table:${id}`);
                return { status: 200, body: tables[id] ?? { epg_listings: [] } };
            }
            return { status: 200, body: answers[action] };
        },
        async postJson() {
            return { status: 500, body: null };
        },
        async text(url) {
            return JSON.stringify(answers[new URL(url).searchParams.get("action") ?? ""]);
        },
    };
    const idOf = (model: AppModel, name: string) => model.state.lineup!.channels.find((c) => c.name === name)!.id;

    it("lists events from the sports channels' guides and their names, reading only sports channels", async () => {
        const model = new AppModel(services(xtream));
        await model.useLogin(login);
        asked.length = 0;
        await model.openEvents();
        expect(model.state.route).toBe("events");
        expect(asked.sort()).toEqual(["table:10", "table:11", "table:12", "table:13"]);
        expect(model.state.eventsLoad).toMatchObject({ busy: false, result: { ok: true } });
        const [game, ufc, later] = model.state.events;
        expect(game).toMatchObject({ title: "Leafs vs. Canucks", channels: [idOf(model, "US| ESPN"), idOf(model, "CA| TSN 1")] });
        expect(ufc).toMatchObject({ title: "UFC 312", timeKnown: false });
        expect(ufc.channels).toHaveLength(2);
        expect(later.title).toBe("Raptors vs. Celtics");
        // A second visit reads nothing again.
        asked.length = 0;
        await model.openEvents();
        expect(asked).toEqual([]);
    });

    it("auto-tunes one channel at a time, best first, moving on when one fails and remembering what worked", async () => {
        let open = 0;
        let most = 0;
        const opened: string[] = [];
        const player = {
            open: async (url: string) => {
                opened.push(url);
                most = Math.max(most, ++open);
            },
            close: async () => void open--,
        };
        const model = new AppModel({ ...services(xtream), player });
        await model.useLogin(login);
        await model.openEvents();
        const ufc = model.state.events.find((e) => e.title === "UFC 312")!;
        await model.tuneEvent(ufc.title, ufc.channels);
        // No country first, then the Spanish one.
        expect(model.state.tune).toMatchObject({ title: "UFC 312", at: 0, found: false });
        expect(model.state.tune!.ids).toEqual([idOf(model, "PPV 1: UFC 312"), idOf(model, "ES| PPV 2: UFC 312")]);
        model.playback("failed");
        await until(() => model.state.tune?.at === 1);
        await until(() => opened.length === 2);
        model.playback("playing");
        expect(model.state.tune).toMatchObject({ at: 1, found: true });
        expect(model.health.of(idOf(model, "PPV 1: UFC 312")).deadAt).toBeGreaterThan(0);
        expect(model.health.of(idOf(model, "ES| PPV 2: UFC 312")).okAt).toBeGreaterThan(0);
        // Next stream past the last says none played and lets go of the stream.
        await model.nextStream();
        expect(model.state.tune).toMatchObject({ failed: true });
        expect(open).toBe(0);
        expect(most).toBe(1);
        // Try again starts from the best one, which now goes last for having failed.
        await model.retryTune();
        expect(model.state.tune!.at).toBe(0);
        await model.stopPlaying();
        expect(model.state.tune).toBeNull();
        expect(model.state.route).toBe("events");
    });

    it("a later event's bell sets a reminder that auto-tunes it, and nothing found says so", async () => {
        const model = new AppModel(services(xtream));
        await model.useLogin(login);
        await model.openEvents();
        const later = model.state.events.find((e) => e.title === "Raptors vs. Celtics")!;
        await model.toggleEventReminder(later);
        expect(model.hasEventReminder(later)).toBe(true);
        expect(model.state.reminders[0]).toMatchObject({ title: "Raptors vs. Celtics", tune: true });
        (model as unknown as { set(p: object): void }).set({ due: model.state.reminders[0] });
        await model.watchDue();
        expect(model.state.tune).toMatchObject({ title: "Raptors vs. Celtics" });
        expect(model.state.tune!.ids[0]).toBe(idOf(model, "US| ESPN"));
        await model.stopPlaying();
        await model.tuneEvent("Bobsleigh Final");
        expect(model.state.tune).toBeNull();
        expect(model.state.notice?.text).toContain("Bobsleigh Final");
        await model.setPrefs({ tuneWaitS: 12 });
        expect(model.state.prefs.tuneWaitS).toBe(12);
        await model.toggleEventReminder(later);
        expect(model.hasEventReminder(later)).toBe(false);
    });
});

describe("profiles, kids and PINs on Vega", () => {
    const login = { kind: "xtream" as const, server: "http://tv.example", username: "u", password: "p" };
    const answers: Record<string, unknown> = {
        account: { user_info: { auth: 1, status: "Active" } },
        get_live_categories: [
            { category_id: "1", category_name: "News" },
            { category_id: "2", category_name: "Kids" },
        ],
        get_live_streams: [
            { stream_id: 5, name: "Harbour News", category_id: "1" },
            { stream_id: 6, name: "Puffin Cartoons", category_id: "2" },
        ],
        get_vod_categories: [{ category_id: "3", category_name: "Drama" }],
        get_vod_streams: [{ stream_id: 7, name: "Paper Boats", category_id: "3" }],
        get_series_categories: [],
        get_series: [],
    };
    const xtream: Http = {
        async getJson(url) {
            return { status: 200, body: answers[new URL(url).searchParams.get("action") ?? "account"] };
        },
        async postJson(url) {
            return new URL(url).pathname === "/api/auth/token"
                ? { status: 200, body: { status: "ok", token: "tok", email: "" } }
                : { status: 500, body: null };
        },
        async text(url) {
            return JSON.stringify(answers[new URL(url).searchParams.get("action") ?? ""]);
        },
    };
    const hashed = (pin: string) => {
        const salt = Buffer.alloc(16, 3);
        return `v1:${salt.toString("base64")}:${pbkdf2Sync(pin, salt, 120_000, 32, "sha256").toString("base64")}`;
    };
    // Android's sync copy as the server keeps it, 64-bit ids written as bare numbers.
    const backup = `{"fingerprint":"f","changedAt":1,"copy":{"version":3,"playlists":[],
        "profiles":[
            {"id":7340000000000000001,"name":"Mel","colour":1,"kids":false,"favourites":[
                {"itemId":${fnv64("1|7|Paper Boats")},"position":1},{"itemId":${fnv64("0|5|Harbour News")},"position":0}]},
            {"id":7340000000000000002,"name":"Pip","colour":3,"kids":true,"avatar":"fox","favourites":[]},
            {"id":7340000000000000003,"name":"Sam","colour":5,"kids":false,"pinHash":"${hashed("1234")}","favourites":[]}],
        "settings":{"adult_pin":{"s":"${hashed("4321")}"},"followed_events":{"set":["Leafs"]}}}}`;
    const accountFetch: Fetch = async (url) => {
        const path = new URL(url).pathname;
        const body =
            path === "/api/backup" ? backup : path === "/api/auth/token" ? '{"status":"ok","token":"tok","email":""}' : '{"seat":"active"}';
        return { ok: true, status: 200, headers: { get: () => null }, text: async () => body, json: async () => JSON.parse(body) };
    };
    const make = (store = memoryStore()) => new AppModel({ ...services(xtream, store), fetch: accountFetch });

    it("asks who's watching, starts each profile with the account's favourites, and reads followed teams", async () => {
        const store = memoryStore();
        const model = make(store);
        await model.useLogin(login);
        await model.refreshLibrary();
        await model.accountLinked("link");
        expect(model.state.route).toBe("profiles");
        expect(model.state.profiles.profiles.map((p) => p.name)).toEqual(["Mel", "Pip", "Sam"]);
        expect(model.state.follows).toEqual(["Leafs"]);
        const [mel, pip, sam] = model.state.profiles.profiles;
        expect(mel.id).toBe("7340000000000000001");

        expect(model.lockFor(mel)).toBe("none");
        await model.useProfile(mel.id);
        expect(model.state.route).toBe("live");
        expect([...model.state.favourites]).toEqual(["5", "m7"]);
        // Once only: unstarring stays unstarred.
        await model.toggleFavourite("5", "Harbour News");
        await model.useProfile(mel.id);
        expect([...model.state.favourites]).toEqual(["m7"]);

        // Moving into a kids profile never asks; it sees kids channels only, and its own favourites.
        expect(model.lockFor(pip)).toBe("none");
        await model.useProfile(pip.id);
        expect(model.shownChannels().map((c) => c.name)).toEqual(["Puffin Cartoons"]);
        expect(model.shownCategories("live").map((c) => c.name)).toEqual(["Kids"]);
        expect(model.shownTitles("movies")).toEqual([]);
        expect(model.state.favourites.size).toBe(0);

        // Leaving it needs the parental PIN; a profile with its own PIN needs that.
        expect(model.lockFor(mel)).toBe("parental");
        expect(model.lockFor(sam)).toBe("profile");
        expect(await model.checkPin("1111", sam.pinHash)).toBe("wrong");
        expect(await model.checkPin("1234", sam.pinHash)).toBe("ok");
        expect(await model.checkPin("4321", model.state.profiles.parentalPin)).toBe("ok");

        // Opening the app again asks again, the last one used first in line.
        const again = make(store);
        await again.boot();
        expect(again.state.route).toBe("profiles");
        expect(again.currentProfile()?.name).toBe("Pip");
        expect(again.state.follows).toEqual(["Leafs"]);
    }, 20_000);

    it("forgets the profiles when the account signs out", async () => {
        const model = make();
        await model.useLogin(login);
        await model.accountLinked("link");
        await model.useProfile(model.state.profiles.profiles[1].id);
        expect(model.kids()).toBe(true);
        await model.signOutOfAccount().catch(() => undefined);
        expect(model.state.profiles.profiles).toEqual([]);
        expect(model.kids()).toBe(false);
        expect(model.shownChannels().length).toBe(2);
    });
});

describe("your own lists, account Favourites and renamed channels on Vega", () => {
    const login = { kind: "xtream" as const, server: "http://tv.example", username: "u", password: "p" };
    const answers: Record<string, unknown> = {
        account: { user_info: { auth: 1, status: "Active" } },
        get_live_categories: [{ category_id: "1", category_name: "News" }],
        get_live_streams: [
            { stream_id: 5, name: "Harbour News", category_id: "1" },
            { stream_id: 6, name: "Puffin Weather", category_id: "1" },
        ],
        get_vod_categories: [{ category_id: "3", category_name: "Drama" }],
        get_vod_streams: [
            { stream_id: 7, name: "Paper Boats", category_id: "3" },
            { stream_id: 8, name: "Quiet Orchard", category_id: "3" },
        ],
        get_series_categories: [],
        get_series: [],
    };
    const xtream: Http = {
        async getJson(url) {
            return { status: 200, body: answers[new URL(url).searchParams.get("action") ?? "account"] };
        },
        async postJson() {
            return { status: 500, body: null };
        },
        async text(url) {
            return JSON.stringify(answers[new URL(url).searchParams.get("action") ?? ""]);
        },
    };
    const news = fnv64("0|5|Harbour News");
    const weather = fnv64("0|6|Puffin Weather");
    const boats = fnv64("1|7|Paper Boats");
    const orchard = fnv64("1|8|Quiet Orchard");
    const start = `{"fingerprint":"f","changedAt":1,"copy":{"version":3,"playlists":[],
        "profiles":[{"id":7340000000000000001,"name":"Mel","colour":1,"kids":false,"favourites":[{"itemId":${orchard},"position":0}],
            "lists":[{"id":7340000000000000099,"name":"Weekend","position":0,"createdAt":1,"items":[
                {"itemId":${weather},"addedAt":2,"position":1},{"itemId":${news},"addedAt":1,"position":0},{"itemId":${boats},"addedAt":3,"position":2}]}]}],
        "channelEdits":[{"itemId":${news},"name":"Harbour 24","logo":"https://logos.example/h.png"}],
        "settings":{}}}`;
    /** The account's backup as the server keeps it: a version, and a PUT that must name it. */
    const server = (premium = true) => {
        const s = { text: start, version: 4, puts: 0, down: false };
        const fetch: Fetch = async (url, init) => {
            const path = new URL(url).pathname;
            const reply = (status: number, body: string, version?: number) => ({
                ok: status < 300,
                status,
                headers: { get: (h: string) => (h === "X-Backup-Version" && version !== undefined ? String(version) : null) },
                text: async () => body,
                json: async () => JSON.parse(body),
            });
            if (path === "/api/backup" && init?.method === "PUT") {
                if (s.down) return reply(503, "{}");
                const wanted = (init.headers as Record<string, string>)["If-Match"];
                if (wanted !== `"${s.version}"`) return reply(409, "{}");
                s.text = init.body ?? "";
                s.version += 1;
                s.puts += 1;
                return reply(200, "{}");
            }
            if (path === "/api/backup") return reply(200, s.text, s.version);
            if (path === "/api/auth/token") return reply(200, '{"status":"ok","token":"tok","email":""}');
            return reply(200, premium ? '{"seat":"active"}' : '{"seat":"none"}');
        };
        return { s, fetch };
    };
    const ready = async (premium = true) => {
        const { s, fetch } = server(premium);
        const model = new AppModel({ ...services(xtream), fetch });
        await model.useLogin(login);
        await model.refreshLibrary();
        await model.accountLinked("link");
        await model.useProfile("7340000000000000001");
        return { model, s };
    };

    it("shows the account's lists after Favourites, a renamed channel under its new name, and finds it by either", async () => {
        const { model } = await ready();
        expect(model.canUseLists()).toBe(true);
        expect([...model.state.favourites]).toEqual(["m8"]);
        expect(model.shownChannels().map((c) => c.name)).toEqual(["Harbour 24", "Puffin Weather"]);
        expect(model.shownChannels()[0].logo).toBe("https://logos.example/h.png");
        // Channels in the order they were added; movies in the playlist's.
        const [live] = model.listCategories("live");
        expect(live).toMatchObject({ name: "Weekend", count: 2 });
        model.pickCategory(live.id);
        expect(model.visibleChannels().map((c) => c.name)).toEqual(["Harbour 24", "Puffin Weather"]);
        expect(model.listCategories("movies")).toMatchObject([{ name: "Weekend", count: 1 }]);
        for (const q of ["harbour 24", "harbour news"]) {
            model.setQuery(q);
            expect(model.results().map((f) => (f.kind === "live" ? f.channel.name : f.title.name))).toEqual(["Harbour 24"]);
        }
    });

    it("adds to a list, makes a new one with the item inside, deletes it, and stars into the account", async () => {
        const { model, s } = await ready();
        const list = model.myLists()[0];
        model.openAddToList("m8", "Quiet Orchard");
        await model.setInList(list.id, true);
        expect(s.puts).toBe(1);
        expect(model.myLists()[0].items).toContain(orchard);
        expect(model.state.listSave).toEqual({ busy: null, problem: null });

        expect(await model.newList("Rainy days")).toBe(true);
        const made = model.myLists().find((l) => l.name === "Rainy days");
        expect(made?.items).toEqual([orchard]);
        expect(s.text).toContain('"Rainy days"');
        // 64-bit ids stay whole in what goes back.
        expect(s.text).toContain(`"itemId":${orchard}`);
        model.closeAddToList();

        expect(await model.deleteList(made!.id)).toBe(true);
        expect(model.myLists().map((l) => l.name)).toEqual(["Weekend"]);

        await model.toggleFavourite("5", "Harbour 24");
        expect([...model.state.favourites].sort()).toEqual(["5", "m8"]);
        expect(s.text).toContain(`"itemId":${news}`);
        expect(s.puts).toBe(4);
    });

    it("puts a star back and says why when the account didn't take it", async () => {
        const { model, s } = await ready();
        s.down = true;
        await model.toggleFavourite("m8", "Quiet Orchard");
        expect(model.state.favourites.has("m8")).toBe(true);
        expect(model.state.notice?.text).toBeTruthy();
        model.openAddToList("5", "Harbour 24");
        await model.setInList(model.myLists()[0].id, false);
        expect(model.state.listSave.problem).toBe("offline");
        expect(model.myLists()[0].items).toContain(news);
    });

    it("keeps lists to Premium: a free account stars on the stick, and sees no lists", async () => {
        const { model, s } = await ready(false);
        expect(model.canUseLists()).toBe(false);
        expect(model.listCategories("live")).toEqual([]);
        expect(s.puts).toBe(0);
    });
});

describe("Simkl on Vega", () => {
    const login = { kind: "xtream" as const, server: "http://tv.example", username: "u", password: "p" };
    const answers: Record<string, unknown> = {
        account: { user_info: { auth: 1, status: "Active" } },
        get_live_categories: [{ category_id: "1", category_name: "News" }],
        get_live_streams: [{ stream_id: 5, name: "Harbour News", category_id: "1" }],
        get_vod_categories: [],
        get_vod_streams: [],
        get_series_categories: [],
        get_series: [],
    };
    const xtream: Http = {
        async getJson(url) {
            return { status: 200, body: answers[new URL(url).searchParams.get("action") ?? "account"] };
        },
        async postJson() {
            return { status: 500, body: null };
        },
        async text(url) {
            return JSON.stringify(answers[new URL(url).searchParams.get("action") ?? ""]);
        },
    };
    const make = (backup: string) => {
        const sent: { path: string; body: any }[] = [];
        const polls = ["authorization_pending", "slow_down"];
        const fetch: Fetch = async (url, init) => {
            const u = new URL(url);
            const reply = (status: number, body: unknown) => ({
                ok: status < 300,
                status,
                headers: { get: () => null },
                text: async () => JSON.stringify(body),
                json: async () => body,
            });
            if (u.host === "api.simkl.com") {
                sent.push({ path: u.pathname, body: init?.body?.startsWith("{") ? JSON.parse(init.body) : init?.body });
                if (u.pathname === "/oauth2/device")
                    return reply(200, {
                        device_code: "d",
                        user_code: "QW7E",
                        verification_uri: "https://simkl.com/pin",
                        expires_in: 900,
                        interval: 1,
                    });
                if (u.pathname === "/oauth2/token") {
                    const error = polls.shift();
                    return error ? reply(400, { error }) : reply(200, { access_token: "a", refresh_token: "r", expires_in: 90 * 86400 });
                }
                if (u.pathname === "/users/settings") return reply(200, { user: { name: "Robin" } });
                return reply(201, {});
            }
            if (u.pathname === "/api/backup") return { ...reply(200, null), text: async () => backup };
            if (u.pathname === "/api/auth/token") return reply(200, { status: "ok", token: "tok", email: "" });
            return reply(200, { seat: "active" });
        };
        let at = 0;
        const player = { open: async () => {}, close: async () => {}, position: () => ({ at, length: 2000 }) };
        const model = new AppModel({ ...services(xtream), fetch, player, simklId: "cid" });
        return { model, sent, seek: (s: number) => (at = s) };
    };
    const noProfiles = '{"fingerprint":"f","changedAt":1,"copy":{"version":3,"playlists":[],"profiles":[],"settings":{}}}';
    const movie = {
        id: "m7",
        titleId: "m7",
        title: "Paper Boats",
        url: "http://tv.example/movie/u/p/7.mp4",
        scrobble: { type: "movie" as const, title: "Paper Boats", year: 2025 },
    };

    afterEach(() => jest.useRealTimers());

    it("connects with a code, then tells Simkl a movie started, paused and stopped", async () => {
        const { model, sent, seek } = make(noProfiles);
        await model.useLogin(login);
        await model.accountLinked("link");
        expect(model.simklOn()).toBe(true);
        jest.useFakeTimers();
        const connecting = model.connectSimkl();
        await jest.advanceTimersByTimeAsync(0);
        expect(model.state.simkl.code?.userCode).toBe("QW7E");
        expect(model.state.simkl.busy).toBe(true);
        await jest.advanceTimersByTimeAsync(1000); // waiting
        await jest.advanceTimersByTimeAsync(1000); // slow down: the next ask waits 6 s
        await jest.advanceTimersByTimeAsync(5000);
        expect(model.state.simkl.signedIn).toBe(false);
        await jest.advanceTimersByTimeAsync(1000);
        await connecting;
        jest.useRealTimers();
        expect(model.state.simkl).toMatchObject({ signedIn: true, user: "Robin", code: null, busy: false, problem: null });

        await model.playVod(movie);
        model.playback("playing");
        seek(500);
        model.pauseChanged(true);
        model.playback("playing"); // the picture coming back after a pause isn't a second start
        seek(1700);
        await model.stopPlaying();
        await until(() => sent.filter((s) => s.path.startsWith("/scrobble")).length === 3);
        expect(sent.filter((s) => s.path.startsWith("/scrobble"))).toEqual([
            { path: "/scrobble/start", body: { movie: { title: "Paper Boats", year: 2025 }, progress: 0 } },
            { path: "/scrobble/pause", body: { movie: { title: "Paper Boats", year: 2025 }, progress: 25 } },
            { path: "/scrobble/stop", body: { movie: { title: "Paper Boats", year: 2025 }, progress: 85 } },
        ]);

        // Scrobble off sends nothing; live TV and catch-up never do.
        await model.setSimklScrobble(false);
        await model.playVod(movie);
        model.playback("playing");
        await model.stopPlaying();
        await model.setSimklScrobble(true);
        await model.playVod({ ...movie, replay: true });
        model.playback("playing");
        await model.stopPlaying();
        await new Promise((r) => setTimeout(r, 20));
        expect(sent.filter((s) => s.path.startsWith("/scrobble")).length).toBe(3);
    });

    it("Back stops waiting for the code", async () => {
        const { model } = make(noProfiles);
        await model.useLogin(login);
        await model.accountLinked("link");
        jest.useFakeTimers();
        void model.connectSimkl();
        await jest.advanceTimersByTimeAsync(0);
        model.cancelSimkl();
        await jest.advanceTimersByTimeAsync(10_000);
        expect(model.state.simkl).toMatchObject({ signedIn: false, busy: false, code: null });
    });

    it("takes the phone's Simkl sign-in from the account's backup", async () => {
        const backup = `{"fingerprint":"f","changedAt":1,"copy":{"version":3,"playlists":[],"profiles":[],"settings":{},
            "simkl":{"access":"a","refresh":"r","expiresAt":${Date.now() + 30 * 86400_000},"username":"Robin"}}}`;
        const { model } = make(backup);
        await model.useLogin(login);
        await model.accountLinked("link");
        await until(() => model.state.simkl.signedIn);
        expect(model.state.simkl.user).toBe("Robin");
    });
});
