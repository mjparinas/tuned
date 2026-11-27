// The shared search, favourites and TMDB details (docs/decisions/vega.md, tizen.md).
import assert from "node:assert/strict";
import { test } from "node:test";
import { Favourites, favouritesKey } from "./favourites.ts";
import { MemoryStore } from "./platform.ts";
import { matcher, search, searchText } from "./search.ts";
import { fakeFetch } from "./test-fetch.ts";
import { runtimeText, Tmdb, tmdbQuery } from "./tmdb.ts";

test("search takes off accents and punctuation, and every typed word must start a word", () => {
    assert.equal(searchText("Télé-Québec HD"), "tele quebec hd");
    assert.equal(searchText("Łódź: Żurek & Ćma"), "lodz zurek cma");
    const m = matcher("tele que")!;
    assert.equal(m("Télé-Québec"), true);
    assert.equal(m("Hotel Quebec"), false);
    assert.equal(matcher("  "), null);
    const items = ["Northside News", "News at Noon", "Newsroom", "Kids"];
    assert.deepEqual(
        search(items, "news", (x) => x),
        ["Northside News", "News at Noon", "Newsroom"],
    );
    assert.deepEqual(
        search(items, "new", (x) => x, 2),
        ["Northside News", "News at Noon"],
    );
});

test("favourites are kept per playlist and survive a restart", async () => {
    const store = new MemoryStore();
    const a = new Favourites(store, favouritesKey("p1"));
    const seen: number[] = [];
    a.onChange((ids) => seen.push(ids.size));
    await a.load();
    assert.equal(await a.toggle("m7"), true);
    assert.equal(await a.toggle("c1"), true);
    assert.equal(await a.toggle("c1"), false);
    assert.deepEqual(seen, [0, 1, 2, 1]);
    const again = new Favourites(store, "favourites.p1");
    assert.deepEqual([...(await again.load())], ["m7"]);
    assert.equal((await new Favourites(store, favouritesKey("p2")).load()).size, 0);
    await store.set("favourites.p3", "not json");
    assert.equal((await new Favourites(store, favouritesKey("p3")).load()).size, 0);
});

test("TMDB cleans the provider's name, finds the title once and keeps it", async () => {
    assert.deepEqual(tmdbQuery("EN | Paper Boats (2025) 4K"), { title: "Paper Boats", year: 2025 });
    assert.deepEqual(tmdbQuery("[FR] Le Film - 1999"), { title: "Le Film", year: 1999 });
    assert.equal(runtimeText(112), "1h 52m");
    assert.equal(runtimeText(48), "48m");
    assert.equal(runtimeText(null), null);

    const fetch = fakeFetch((url) => {
        if (url.pathname === "/3/search/movie")
            return { body: { results: url.searchParams.get("primary_release_year") === "2025" ? [{ id: 9 }] : [] } };
        if (url.pathname === "/3/movie/9")
            return {
                body: {
                    id: 9,
                    title: "Paper Boats",
                    overview: "Two friends.",
                    release_date: "2025-03-01",
                    vote_average: 7.84,
                    runtime: 112,
                    genres: [{ name: "Drama" }],
                    poster_path: "/p.jpg",
                    backdrop_path: "/b.jpg",
                    credits: { cast: [{ id: 1, name: "Ada Lake", character: "June", profile_path: "/a.jpg" }] },
                },
            };
        if (url.pathname === "/3/search/tv") return { body: { results: [{ id: 4 }] } };
        if (url.pathname === "/3/tv/4") return { body: { id: 4, name: "Harbour Street", episode_run_time: [44], number_of_seasons: 2 } };
        if (url.pathname === "/3/tv/4/season/1")
            return {
                body: {
                    episodes: [
                        { episode_number: 1, name: "A Ship Comes In" },
                        { episode_number: 2, name: "Episode 2" },
                    ],
                },
            };
        return undefined;
    });
    let now = 1000;
    const store = new MemoryStore();
    const tmdb = new Tmdb(fetch, store, "k3y", () => now);
    const movie = await tmdb.details("movie", "EN | Paper Boats (2025) 4K");
    assert.deepEqual(movie, {
        tmdbId: 9,
        title: "Paper Boats",
        overview: "Two friends.",
        year: 2025,
        rating: 7.8,
        runtime: 112,
        genres: ["Drama"],
        poster: "https://image.tmdb.org/t/p/w500/p.jpg",
        backdrop: "https://image.tmdb.org/t/p/w1280/b.jpg",
        cast: [{ id: 1, name: "Ada Lake", character: "June", photo: "https://image.tmdb.org/t/p/w185/a.jpg" }],
        seasons: null,
    });
    assert.match(fetch.calls[0].url, /api_key=k3y/);
    const asked = fetch.calls.length;
    await tmdb.details("movie", "EN | Paper Boats (2025) 4K");
    assert.equal(fetch.calls.length, asked, "the second look comes from the store");

    const show = await tmdb.details("show", "Harbour Street");
    assert.equal(show?.runtime, 44);
    const episodes = await tmdb.season(show!.tmdbId, 1);
    assert.deepEqual(
        episodes.map((e) => [e.episode, e.name]),
        [
            [1, "A Ship Comes In"],
            [2, null],
        ],
    );

    // No match is asked about again after a day, not on every look.
    assert.equal(await tmdb.details("movie", "Nothing Like It"), null);
    const before = fetch.calls.length;
    assert.equal(await tmdb.details("movie", "Nothing Like It"), null);
    assert.equal(fetch.calls.length, before);
    now += 25 * 3600 * 1000;
    await tmdb.details("movie", "Nothing Like It");
    assert.ok(fetch.calls.length > before);

    // A v4 token goes in a header; no key turns TMDB off.
    const tokenFetch = fakeFetch(() => ({ body: { results: [] } }));
    await new Tmdb(tokenFetch, new MemoryStore(), "eyJabc").details("movie", "X");
    assert.equal(tokenFetch.calls[0].init.headers?.Authorization, "Bearer eyJabc");
    assert.doesNotMatch(tokenFetch.calls[0].url, /api_key/);
    assert.equal(await new Tmdb(tokenFetch, new MemoryStore(), "").details("movie", "X"), null);
});
