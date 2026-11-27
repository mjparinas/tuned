import type { Fetch } from "@tuned/shared/platform";
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
