// The preview's scenes: each of the stick's screens with made-up sample data (no real provider, channel or brand,
// as store screenshots require), picked by ?scene=. preview/shoot.mjs draws every one at 1920 × 1080.
import * as React from "react";
import { createRoot } from "react-dom/client";
import { View } from "react-native";
import { AppModel, CONTINUE, DEFAULT_PREFS, type State } from "../src/app/model";
import type { Http } from "../src/core/http";
import type { Channel, Library, Lineup, Title } from "../src/core/models";
import { memoryStore } from "../src/core/storage";
import { ReminderBanner, Shell } from "../src/App";
import { AddToList } from "../src/ui/AddToList";
import { BrowseScreen } from "../src/ui/BrowseScreen";
import { PinPad, ProfilesScreen } from "../src/ui/ProfilesScreen";
import { EventsScreen } from "../src/ui/EventsScreen";
import { LiveScreen } from "../src/ui/LiveScreen";
import { LoadingScreen } from "../src/ui/LoadingScreen";
import { ManualScreen } from "../src/ui/ManualScreen";
import { PlayerScreen } from "../src/ui/PlayerScreen";
import { SearchScreen } from "../src/ui/SearchScreen";
import { SettingsScreen } from "../src/ui/SettingsScreen";
import { SignInScreen } from "../src/ui/SignInScreen";
import { TitleScreen } from "../src/ui/TitleScreen";
import { VegaPlayer } from "../src/platform/VegaPlayer";
import { chooseLanguage, rightToLeft, t as tr0 } from "../src/ui/strings";

const offline: Http = {
    text: () => Promise.reject(new Error("preview")),
    getJson: () => Promise.reject(new Error("preview")),
    postJson: () => Promise.reject(new Error("preview")),
};

const CATEGORIES = [
    ["1", "News"],
    ["2", "Sports"],
    ["3", "Kids"],
    ["4", "Movies"],
    ["5", "Music"],
    ["6", "Documentaries"],
    ["7", "Local"],
] as const;
const NAMES: Record<string, string[]> = {
    "1": ["Northside News", "Harbour News 24", "City Desk", "World Report", "Morning Wire", "Metro Update"],
    "2": ["Field One", "Field Two", "Rink Live", "Court Side", "Track & Trail", "Goal Line"],
    "3": ["Little Owls", "Cartoon Corner", "Story Time", "Bright Sparks"],
    "4": ["Classic Cinema", "Late Show Movies", "Picture House", "Reel Drama"],
    "5": ["Hit Mix", "Jazz Lounge", "Country Roads"],
    "6": ["Planet Wild", "Deep Blue", "History Hall", "Science Now"],
    "7": ["Valley TV", "Lakeside Community", "Coastline"],
};

function sampleLineup(): Lineup {
    const channels: Channel[] = [];
    for (const [id] of CATEGORIES) {
        for (const name of NAMES[id]) {
            const number = channels.length + 101;
            channels.push({
                id: `c${number}`,
                name,
                number,
                categoryId: id,
                logo: null,
                url: `http://provider.example/live/${number}.ts`,
                streamId: number,
            });
        }
    }
    return { categories: CATEGORIES.map(([id, name]) => ({ id, name })), channels, savedAt: Date.now() };
}

/** A made-up poster: a gradient and the title, drawn as a picture so the grid looks like a real one. */
function poster(name: string, hue: number): string {
    const words = name.split(" ");
    const lines = words.reduce<string[]>((out, w) => {
        const last = out[out.length - 1];
        if (last && (last + " " + w).length <= 11) out[out.length - 1] = `${last} ${w}`;
        else out.push(w);
        return out;
    }, []);
    const text = lines
        .map(
            (l, i) =>
                `<text x="100" y="${200 + i * 30 - lines.length * 15}" font-family="Georgia, serif" font-size="26" font-weight="700" fill="#fff" text-anchor="middle">${l}</text>`,
        )
        .join("");
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="200" height="300"><defs><linearGradient id="g" x1="0" y1="0" x2="0.6" y2="1"><stop offset="0" stop-color="hsl(${hue},55%,42%)"/><stop offset="1" stop-color="hsl(${(hue + 40) % 360},60%,14%)"/></linearGradient></defs><rect width="200" height="300" fill="url(#g)"/><circle cx="150" cy="70" r="46" fill="hsla(${(hue + 180) % 360},70%,70%,0.25)"/>${text}</svg>`;
    return `data:image/svg+xml;base64,${btoa(svg)}`;
}

const MOVIE_CATEGORIES = [
    ["10", "New releases"],
    ["11", "Action"],
    ["12", "Comedy"],
    ["13", "Drama"],
    ["14", "Family"],
    ["15", "Documentaries"],
] as const;
const MOVIES: [string, string, number, number][] = [
    ["The Long Harbour", "10", 2026, 7.4],
    ["Northern Lights", "10", 2026, 6.9],
    ["Paper Boats", "13", 2025, 7.8],
    ["Last Train East", "11", 2025, 6.6],
    ["Kettle Valley", "12", 2024, 7.1],
    ["Glass Mountain", "11", 2025, 6.2],
    ["The Quiet Field", "13", 2023, 8.1],
    ["Small Wonders", "14", 2024, 7.0],
    ["Salt and Cedar", "13", 2022, 7.3],
    ["Fast Water", "11", 2026, 6.4],
    ["Mostly Sunny", "12", 2025, 6.8],
    ["Deep Ice", "15", 2024, 7.9],
    ["The Orchard", "13", 2021, 7.5],
    ["Lantern Night", "14", 2023, 7.2],
    ["Two Rivers", "10", 2026, 7.0],
];
const SHOW_CATEGORIES = [
    ["20", "Drama"],
    ["21", "Comedy"],
    ["22", "Kids"],
    ["23", "Nature"],
] as const;
const SHOWS: [string, string, number, number][] = [
    ["Harbour Street", "20", 2024, 8.2],
    ["The Night Desk", "20", 2025, 7.6],
    ["Office Hours", "21", 2023, 7.4],
    ["Little Explorers", "22", 2022, 7.1],
    ["Wild Coast", "23", 2025, 8.4],
    ["Back Roads", "21", 2024, 7.0],
    ["Northbound", "20", 2026, 7.8],
    ["Kitchen Rules", "21", 2023, 6.9],
    ["Under the Pines", "23", 2021, 8.0],
    ["Robot Friends", "22", 2025, 7.3],
];
const EPISODE_NAMES = [
    "A Ship Comes In",
    "The Night Shift",
    "Low Tide",
    "Lighthouse Keeper",
    "Fog Warning",
    "Shore Leave",
    "The Storm",
    "Homecoming",
];

function sampleLibrary(): Library {
    const make = (list: [string, string, number, number][], kind: "movie" | "show"): Title[] =>
        list.map(([name, categoryId, year, rating], i) => ({
            id: `${kind[0]}${i + 1}`,
            kind,
            name,
            categoryId,
            poster: poster(name, (i * 47 + (kind === "show" ? 200 : 10)) % 360),
            year,
            rating,
            ...(kind === "movie"
                ? { url: `http://provider.example/movie/${i + 1}.mp4` }
                : {
                      seriesId: i + 1,
                      plot: "A harbour town, a newsroom that never sleeps, and the people who keep both running through one long winter.",
                  }),
        }));
    return {
        movieCategories: MOVIE_CATEGORIES.map(([id, name]) => ({ id, name })),
        showCategories: SHOW_CATEGORIES.map(([id, name]) => ({ id, name })),
        movies: make(MOVIES, "movie"),
        shows: make(SHOWS, "show"),
        savedAt: Date.now(),
    };
}

const library = sampleLibrary();
const episodes = [1, 2].flatMap((season) =>
    EPISODE_NAMES.slice(0, season === 1 ? 8 : 6).map((name, i) => ({
        id: `e${season}${i + 1}`,
        season,
        episode: i + 1,
        name: season === 1 ? name : `${name} Part Two`,
        url: `http://provider.example/series/${season}${i + 1}.mkv`,
    })),
);

function model(patch: Partial<State>): AppModel {
    const m = new AppModel({
        store: memoryStore(),
        http: offline,
        fetch: () => Promise.reject(new Error("The preview is offline")),
        random: (n) => crypto.getRandomValues(new Uint8Array(n)),
        player: new VegaPlayer(),
        deviceName: "Preview",
    });
    m.state = { ...m.state, ...patch };
    return m;
}

const hour = 3600_000;
const at = (h: number, m: number) => new Date(2026, 10, 27, h, m).getTime();
const lineup = sampleLineup();
const account = { email: "you@example.com", premium: true, seat: "active" as const, checkedAt: Date.now() };

const PROFILES = {
    profiles: [
        { id: "1", name: "Alex", colour: 0, kids: false, pinHash: null, avatar: null, favourites: [] },
        { id: "2", name: "Jordan", colour: 2, kids: false, pinHash: "v1:preview:only", avatar: "owl", favourites: [] },
        { id: "3", name: "Robin", colour: 5, kids: true, pinHash: null, avatar: "fox", favourites: [] },
        { id: "4", name: "Sam", colour: 4, kids: false, pinHash: null, avatar: "rocket", favourites: [] },
    ],
    parentalPin: "v1:preview:only",
};

/** Alex's own lists from the account, filled with sample channels and movies by Android's ids, and one renamed channel. */
function withLists(m: AppModel): AppModel {
    const a = (id: string) => m.androidIdOf(id) ?? "";
    m.state = {
        ...m.state,
        lists: {
            lists: {
                "1": [
                    { id: "9001", name: "Game night", items: [a("c107"), a("c110"), a("c108")] },
                    { id: "9002", name: "Morning news", items: [a("c101"), a("c102")] },
                    { id: "9003", name: "Cosy movies", items: [a("m3"), a("m7"), a("m12")] },
                ],
            },
            edits: { [a("c103")]: { name: "Harbour Local", logo: null } },
        },
    };
    return m;
}

function Scene({ name }: { name: string }) {
    const player = React.useMemo(() => new VegaPlayer(), []);
    switch (name) {
        case "signin":
            return (
                <SignInScreen
                    model={model({})}
                    mode="playlist"
                    view={{
                        kind: "showing",
                        code: "K7QF-29MD",
                        site: "www.tunedplayer.com/tv",
                        digits: "4821",
                        qr: "https://www.tunedplayer.com/tv#K7QF29MD.BPreviewKeyOnlyNotARealKeyAtAll",
                    }}
                />
            );
        case "signin-offline":
            return <SignInScreen model={model({})} mode="playlist" view={{ kind: "offline" }} />;
        case "manual":
            return <ManualScreen model={model({})} />;
        case "loading": {
            const m = model({
                route: "loading",
                loading: {
                    progress: {
                        step: 3,
                        steps: 3,
                        label: "Downloading channels",
                        loaded: 7.4 * 1024 * 1024,
                        total: 18.2 * 1024 * 1024,
                        eta: "about 1 min left",
                    },
                    error: null,
                },
            });
            return <LoadingScreen model={m} state={m.state} />;
        }
        case "loading-failed": {
            const m = model({
                route: "loading",
                loading: { progress: null, error: "The server didn't accept that username and password." },
            });
            return <LoadingScreen model={m} state={m.state} />;
        }
        case "live": {
            const m = model({
                route: "live",
                lineup,
                channelId: "c101",
                guide: {
                    c101: [{ title: "Evening Headlines", start: at(18, 0), end: at(19, 0) }],
                    c102: [{ title: "The Week Ahead", start: at(18, 30), end: at(19, 30) }],
                },
            });
            return <LiveScreen model={m} state={m.state} focusChannel="c101" />;
        }
        case "live-guide":
        case "reminder": {
            const base = at(18, 40);
            const shows = ["Evening Headlines", "Weather Watch", "Market Hour", "City Desk", "Late Edition", "The Week Ahead"];
            const grid: State["grid"] = {};
            lineup.channels.slice(0, 12).forEach((c, i) => {
                const list = [];
                let start = at(17, 0) + (i % 3) * 15 * 60_000;
                for (let k = 0; k < 8; k++) {
                    const length = (k + i) % 3 === 0 ? 60 : 30;
                    list.push({
                        start,
                        stop: start + length * 60_000,
                        title: shows[(k + i) % shows.length],
                        desc: "A made-up programme for the preview.",
                    });
                    start += length * 60_000;
                }
                grid[c.id] = i === 4 ? [] : list;
            });
            const withCatchUp = {
                ...lineup,
                channels: lineup.channels.map((c, i) => (i < 3 ? { ...c, catchUp: { mode: "xc" as const, days: 3, source: null } } : c)),
            };
            const focusStart = grid.c101[2].start;
            const m = model({
                route: "live",
                lineup: withCatchUp,
                channelId: "c101",
                grid,
                prefs: { ...DEFAULT_PREFS, liveGrid: true },
                reminders: [
                    { channelId: "c102", channelName: "", title: grid.c102[3].title, start: grid.c102[3].start, stop: grid.c102[3].stop },
                ],
                due:
                    name === "reminder"
                        ? {
                              channelId: "c103",
                              channelName: lineup.channels[2].name,
                              title: "Late Edition",
                              start: base,
                              stop: base + 3600_000,
                          }
                        : null,
            });
            return (
                <View style={{ flex: 1 }}>
                    <LiveScreen model={m} state={m.state} focusProgramme={{ channelId: "c101", start: focusStart }} fixedNow={base} />
                    <ReminderBanner model={m} state={m.state} />
                </View>
            );
        }
        case "events":
        case "events-reading": {
            // Made-up teams and events: store builds never name a league, team or brand.
            const now = at(19, 10);
            const ev = (title: string, start: number, length: number, channels: string[], timeKnown = true) => ({
                key: title.toLowerCase(),
                title,
                start,
                stop: start + length * 60_000,
                channels,
                timeKnown,
            });
            const events = [
                ev("Harbour Hawks vs. Valley Bears", at(19, 0), 180, ["c107", "c108", "c109"]),
                ev("City Cup Final", at(18, 30), 120, ["c110"]),
                ev("Fight Night 12: Main Card", now, 180, ["c111", "c112"], false),
                ev("Lakeside Open: Final Round", at(20, 0), 240, ["c108"]),
                ev("Coast Kings @ Metro Stars", at(22, 30), 150, ["c109", "c112"]),
                ev("Rink Classic: North vs. South", at(19, 0) + 24 * hour, 150, ["c109"]),
            ];
            const m = model({
                route: "events",
                lineup,
                events,
                reminders: [
                    {
                        channelId: "c109",
                        channelName: "Rink Live",
                        title: events[4].title,
                        start: events[4].start,
                        stop: events[4].stop,
                        tune: true,
                    },
                ],
                eventsLoad:
                    name === "events-reading"
                        ? {
                              busy: true,
                              progress: { step: 1, steps: 1, label: "", loaded: 34, total: 120, eta: "about 1 min left" },
                          }
                        : { busy: false },
            });
            if (name === "events-reading") m.state.eventsLoad.progress!.label = tr0.eventsGuide(34, 120);
            return <EventsScreen model={m} state={m.state} fixedNow={now} showFocused={0} />;
        }
        case "tune-trying":
        case "tune-found":
        case "tune-none": {
            const ids = ["c107", "c108", "c109", "c110", "c111", "c112", "c101", "c102", "c103"];
            const m = model({
                route: "player",
                lineup,
                channelId: name === "tune-trying" ? "c108" : "c109",
                guide: { c109: [{ title: "Harbour Hawks vs. Valley Bears", start: at(19, 0), end: at(22, 0) }] },
                tune: {
                    title: "Harbour Hawks vs. Valley Bears",
                    ids,
                    at: name === "tune-trying" ? 1 : 2,
                    found: name === "tune-found",
                    failed: name === "tune-none",
                },
            });
            return (
                <PlayerScreen
                    model={m}
                    state={m.state}
                    player={player}
                    shown={{ controls: name === "tune-found", playback: name === "tune-trying" ? "starting" : "playing" }}
                />
            );
        }
        case "live-refreshing": {
            const m = model({
                route: "live",
                lineup,
                category: "2",
                channelId: "c107",
                refresh: { busy: true, progress: { step: 2, steps: 3, label: "Downloading categories" } },
            });
            return <LiveScreen model={m} state={m.state} focusChannel="c107" />;
        }
        case "player": {
            const m = model({
                route: "player",
                lineup,
                channelId: "c101",
                guide: {
                    c101: [
                        { title: "Evening Headlines", start: at(18, 0), end: at(19, 0) },
                        { title: "Weather and Traffic", start: at(19, 0), end: at(19, 0) + hour / 2 },
                    ],
                },
            });
            return <PlayerScreen model={m} state={m.state} player={player} shown={{ controls: true, playback: "playing" }} />;
        }
        case "player-failed": {
            const m = model({ route: "player", lineup, channelId: "c107" });
            return <PlayerScreen model={m} state={m.state} player={player} shown={{ playback: "failed" }} />;
        }
        case "profiles": {
            const m = model({ route: "profiles", lineup, account, profiles: PROFILES, profileId: "1" });
            return <ProfilesScreen model={m} state={m.state} showFocused={1} />;
        }
        case "profiles-pin":
        case "profiles-pin-wrong": {
            const m = model({ route: "profiles", lineup, account, profiles: PROFILES, profileId: "1" });
            return (
                <PinPad
                    model={m}
                    title={tr0.pinFor("Jordan")}
                    hash={null}
                    onOk={() => undefined}
                    onCancel={() => undefined}
                    preview={name === "profiles-pin" ? { typed: 3 } : { typed: 0, problem: tr0.pinWrong }}
                />
            );
        }
        case "live-kids": {
            const m = model({ route: "live", lineup, account, profiles: PROFILES, profileId: "3" });
            return <LiveScreen model={m} state={m.state} focusChannel={m.shownChannels()[0]?.id} />;
        }
        case "live-lists": {
            const m = withLists(model({ route: "live", lineup, account, profiles: PROFILES, profileId: "1", channelId: "c107" }));
            m.pickCategory("__list:9001");
            return <LiveScreen model={m} state={m.state} focusChannel="c107" />;
        }
        case "add-to-list":
        case "add-to-list-new": {
            const m = withLists(
                model({
                    route: "live",
                    lineup,
                    account,
                    profiles: PROFILES,
                    profileId: "1",
                    favourites: new Set(["c107"]),
                    listFor: { id: "c107", name: lineup.channels.find((c) => c.id === "c107")?.name ?? "" },
                }),
            );
            return name === "add-to-list" ? (
                <AddToList model={m} state={m.state} showFocused={1} />
            ) : (
                <AddToList model={m} state={m.state} naming="Weekend games" />
            );
        }
        case "movie-lists": {
            const m = withLists(
                model({ route: "movies", lineup, library, account, profiles: PROFILES, profileId: "1", movieCategory: "__list:9003" }),
            );
            return <BrowseScreen model={m} state={m.state} kind="movies" />;
        }
        case "settings-lists": {
            const m = withLists(model({ route: "settings", lineup, account, profiles: PROFILES, profileId: "1" }));
            return <SettingsScreen model={m} state={m.state} />;
        }
        case "settings-profile": {
            const m = model({ route: "settings", lineup, account, profiles: PROFILES, profileId: "1" });
            return <SettingsScreen model={m} state={m.state} />;
        }
        case "settings": {
            const m = model({ route: "settings", lineup, account });
            return <SettingsScreen model={m} state={m.state} />;
        }
        case "settings-playlists": {
            const m = model({
                route: "settings",
                lineup,
                account,
                playlists: [
                    { id: "current", name: "Home" },
                    { id: "p2", name: "provider.example" },
                ],
                playlistId: "current",
            });
            return <SettingsScreen model={m} state={m.state} />;
        }
        case "settings-refreshing": {
            const m = model({
                route: "settings",
                lineup,
                refresh: {
                    busy: true,
                    progress: { step: 3, steps: 3, label: "Downloading channels", loaded: 2.1 * 1024 * 1024, total: null },
                },
            });
            return <SettingsScreen model={m} state={m.state} />;
        }
        case "movies": {
            const m = model({ route: "movies", lineup, library });
            return <BrowseScreen model={m} state={m.state} kind="movies" focusTitle="m1" />;
        }
        case "movies-sorted": {
            const m = model({ route: "movies", lineup, library, prefs: { ...DEFAULT_PREFS, sortMovies: "rating" } });
            return <BrowseScreen model={m} state={m.state} kind="movies" />;
        }
        case "movies-continue": {
            const watching = library.movies
                .slice(2, 5)
                .map((x, i) => ({ id: x.id, titleId: x.id, at: 1200, length: 6000, watchedAt: 10 - i }));
            const m = model({ route: "movies", lineup, library, watching, movieCategory: CONTINUE });
            return <BrowseScreen model={m} state={m.state} kind="movies" />;
        }
        case "movies-loading": {
            const m = model({
                route: "movies",
                lineup,
                libraryLoad: {
                    busy: true,
                    progress: {
                        step: 2,
                        steps: 4,
                        label: "Downloading movies",
                        loaded: 3.6 * 1024 * 1024,
                        total: 9.8 * 1024 * 1024,
                        eta: "less than a minute left",
                    },
                },
            });
            return <BrowseScreen model={m} state={m.state} kind="movies" />;
        }
        case "shows": {
            const m = model({ route: "shows", lineup, library, showCategory: null });
            return <BrowseScreen model={m} state={m.state} kind="shows" focusTitle="s1" />;
        }
        case "live-favourites": {
            const m = model({
                route: "live",
                lineup,
                channelId: "c107",
                favourites: new Set(["c101", "c107", "c110"]),
                notice: { on: true, name: "Field One" },
            });
            return <LiveScreen model={m} state={m.state} focusChannel="c107" />;
        }
        case "search": {
            const m = model({ route: "search", lineup, library, query: "harbour" });
            return <SearchScreen model={m} state={m.state} focusFirst />;
        }
        case "movie-details": {
            const title = library.movies[2];
            const m = model({
                route: "title",
                lineup,
                library,
                title,
                favourites: new Set([title.id]),
                details: {
                    [title.id]: {
                        tmdbId: 1,
                        title: title.name,
                        overview:
                            "Two friends race paper boats down the spring melt every year, until the year one of them moves away and the river decides to go looking.",
                        year: 2025,
                        rating: 7.8,
                        runtime: 112,
                        genres: ["Drama", "Family"],
                        poster: null,
                        backdrop: poster("", 30),
                        cast: ["Ada Lake", "Theo Marsh", "June Park", "Sam Okafor", "Rita Vale", "Leo Brandt"].map((name, i) => ({
                            id: i,
                            name,
                            character: null,
                            photo: null,
                        })),
                        seasons: null,
                    },
                },
            });
            return <TitleScreen model={m} state={m.state} />;
        }
        case "movie": {
            const title = {
                ...library.movies[0],
                plot: "A ferry captain on her last season finds a stowaway with a map of the old harbour, and a reason to stay.",
            };
            const m = model({ route: "title", lineup, library, title, positions: { m1: 2723 } });
            return <TitleScreen model={m} state={m.state} />;
        }
        case "show": {
            const title = library.shows[0];
            const m = model({ route: "title", lineup, library, title, episodes: { [title.id]: episodes }, positions: { e13: 1260 } });
            return <TitleScreen model={m} state={m.state} focusEpisode="e11" />;
        }
        case "movie-player": {
            const m = model({
                route: "player",
                lineup,
                library,
                vod: { id: "e13", title: "Harbour Street", subtitle: "Season 1, episode 3: Low Tide", url: "x" },
            });
            return (
                <PlayerScreen
                    model={m}
                    state={m.state}
                    player={player}
                    shown={{ controls: true, playback: "playing", position: { at: 1262, length: 2880 } }}
                />
            );
        }
        default: {
            const m = model({});
            void m.boot();
            return <Shell model={m} player={player} />;
        }
    }
}

const params = new URLSearchParams(location.search);
const scene = params.get("scene") ?? "";
// ?lang=de-DE draws the scene in that language, as a stick set to it would (docs/decisions/languages.md).
chooseLanguage(params.get("lang") ? [params.get("lang")] : [...navigator.languages]);
document.documentElement.dir = rightToLeft() ? "rtl" : "ltr";
createRoot(document.getElementById("root")!).render(
    <View style={{ width: "100vw" as unknown as number, height: "100vh" as unknown as number }}>
        <Scene name={scene} />
    </View>,
);
