// Reading a playlist on a TV, step by numbered step with real progress (AGENTS.md: "Waiting is never a mystery").
// An Xtream server is read through its JSON API, as Android does (data/XtreamImport.kt), and through its M3U download
// when the API is off; an M3U link is read as it downloads. None of this is a stream connection: the one-connection
// rule (scope.md) is the player's (player.ts).
import { ChannelNumbers, toItem, type Item } from "./catalog.ts";
import { core, M3uReader, Xtream, type Entry } from "./core.ts";
import { xtreamDays } from "./guide.ts";
import { HttpError, readJson, readText, type Progress } from "./http.ts";
import type { PlaylistLogin } from "./login.ts";
import type { AbortSignalLike, Fetch } from "./platform.ts";

/** The steps, for the screen to name in its own language. */
export type StepId = "login" | "live" | "movies" | "shows" | "playlist" | "guide";

export interface Step {
    readonly id: StepId;
    /** 1-based, of `count`. */
    readonly number: number;
    readonly count: number;
    readonly progress: Progress | null;
}

export interface LoadEvents {
    onStep(step: Step): void;
    /** A batch of items in playlist order; the app writes it to its store before the next arrives. */
    onItems(items: Item[]): void | Promise<void>;
}

export interface LoadResult {
    readonly live: number;
    readonly movies: number;
    readonly shows: number;
    /** Where the guide is: the Xtream server's, the playlist header's (`url-tvg`), or the one the person gave. */
    readonly guideUrl: string;
    /** The server's time zone (Xtream's `server_info.timezone`), which catch-up links are read in; null when it doesn't say. */
    readonly timezone?: string | null;
}

/** `server_info.timezone` from an Xtream account answer, such as `Europe/London`. */
export function xtreamTimezone(account: unknown): string | null {
    const info = (account as { server_info?: { timezone?: unknown } } | null)?.server_info;
    const zone = typeof info?.timezone === "string" ? info.timezone.trim() : "";
    return /^[A-Za-z_]+(\/[A-Za-z0-9_+-]+)*$/.test(zone) ? zone : null;
}

/** Why a playlist didn't load, for the screen's words and Try again. */
export class LoadError extends Error {
    readonly reason: "login" | "unreachable" | "empty" | "server";

    constructor(reason: LoadError["reason"], message: string) {
        super(message);
        this.name = "LoadError";
        this.reason = reason;
    }
}

const BATCH = 500;

type Json = Record<string, unknown>;
const text = (o: Json, key: string): string | null => {
    const v = o?.[key];
    return typeof v === "string" ? v : typeof v === "number" ? String(v) : null;
};

export function redactor(login: PlaylistLogin): (t: string) => string {
    if (login.kind === "xtream") {
        const x = new Xtream(login.server, login.username, login.password);
        return (t) => x.redact(t);
    }
    // An M3U link's query often holds the login; keep only the host.
    return (t) => t.split(login.url).join("[playlist link]");
}

export async function loadPlaylist(fetch: Fetch, login: PlaylistLogin, events: LoadEvents, signal?: AbortSignalLike): Promise<LoadResult> {
    return login.kind === "xtream"
        ? loadXtream(fetch, login, events, signal)
        : loadM3u(fetch, login.url, login.guideUrl, events, signal, 1, 1);
}

class Counter {
    live = 0;
    movies = 0;
    shows = 0;
    readonly numbers = new ChannelNumbers();
    private batch: Item[] = [];
    private readonly events: LoadEvents;

    constructor(events: LoadEvents) {
        this.events = events;
    }

    async add(entry: Entry, extra?: Parameters<typeof toItem>[2]): Promise<void> {
        if (entry.kind === "live") this.live++;
        else if (entry.kind === "movie") this.movies++;
        else this.shows++;
        this.batch.push(toItem(entry, this.numbers.number(entry), extra));
        if (this.batch.length >= BATCH) await this.flush();
    }

    async flush(): Promise<void> {
        if (!this.batch.length) return;
        const out = this.batch;
        this.batch = [];
        await this.events.onItems(out);
    }
}

async function loadM3u(
    fetch: Fetch,
    url: string,
    guideUrl: string,
    events: LoadEvents,
    signal: AbortSignalLike | undefined,
    number: number,
    count: number,
): Promise<LoadResult> {
    const reader = new M3uReader();
    const counter = new Counter(events);
    // The first line, which may name the guide (`url-tvg`); it can arrive over several pieces.
    let header = "";
    let headerDone = false;
    events.onStep({ id: "playlist", number, count, progress: null });
    await readText(
        fetch,
        url,
        async (piece) => {
            if (!headerDone) {
                const newline = piece.indexOf("\n");
                header += newline < 0 ? piece : piece.slice(0, newline);
                headerDone = newline >= 0 || header.length > 4096;
            }
            for (const e of reader.feed(piece)) await counter.add(e);
        },
        {
            signal,
            onProgress: (progress) => events.onStep({ id: "playlist", number, count, progress }),
            redact: (t) => t.split(url).join("[playlist link]"),
        },
    ).catch((error: unknown) => {
        if (error instanceof HttpError && (error.status === 401 || error.status === 403)) throw new LoadError("login", error.message);
        if (error instanceof HttpError && error.status) throw new LoadError("server", error.message);
        throw new LoadError("unreachable", (error as Error)?.message ?? String(error));
    });
    for (const e of reader.end()) await counter.add(e);
    await counter.flush();
    if (counter.live + counter.movies + counter.shows === 0) throw new LoadError("empty", "The playlist has no streams");
    const tvg = /url-tvg="([^"]+)"|x-tvg-url="([^"]+)"/i.exec(header);
    return {
        live: counter.live,
        movies: counter.movies,
        shows: counter.shows,
        guideUrl: guideUrl || (tvg?.[1] ?? tvg?.[2] ?? "").split(",")[0].trim(),
    };
}

async function loadXtream(
    fetch: Fetch,
    login: Extract<PlaylistLogin, { kind: "xtream" }>,
    events: LoadEvents,
    signal?: AbortSignalLike,
): Promise<LoadResult> {
    const x = new Xtream(login.server, login.username, login.password);
    const redact = (t: string) => x.redact(t);
    const COUNT = 4;
    events.onStep({ id: "login", number: 1, count: COUNT, progress: null });
    let account: unknown;
    try {
        account = await readJson(fetch, x.accountUrl, { signal, redact });
    } catch (error) {
        if (error instanceof HttpError && (error.status === 401 || error.status === 403))
            throw new LoadError("login", "The server didn't accept the login");
        if (error instanceof HttpError && error.status === 0 && !/readable/.test(error.message))
            throw new LoadError("unreachable", error.message);
        // The API is off on this server, or answers with a page: read its M3U download instead.
        return loadM3u(fetch, x.playlistUrl, x.guideUrl, events, signal, 2, 2);
    }
    const body = JSON.stringify(account ?? {});
    if (core.xtreamAccepted(body) === false) throw new LoadError("login", "The server didn't accept the login");

    const counter = new Counter(events);
    const get = async (action: string, step: Step["id"], number: number): Promise<Json[]> => {
        const answer = await readJson(fetch, x.actionUrl(action), {
            signal,
            redact,
            onProgress: (progress) => events.onStep({ id: step, number, count: COUNT, progress }),
        }).catch((error: unknown) => {
            throw new LoadError("server", redact((error as Error)?.message ?? String(error)));
        });
        return Array.isArray(answer) ? (answer.filter((o) => o && typeof o === "object") as Json[]) : [];
    };
    const categories = async (action: string, step: Step["id"], number: number) =>
        new Map((await get(action, step, number)).map((o) => [text(o, "category_id") ?? "", text(o, "category_name") ?? ""] as const));

    let order = 0;
    const streams = async (kind: "live" | "movie", list: string, cats: string, step: Step["id"], number: number) => {
        events.onStep({ id: step, number, count: COUNT, progress: null });
        const names = await categories(cats, step, number);
        for (const o of await get(list, step, number)) {
            const id = text(o, "stream_id");
            const name = text(o, "name")?.trim();
            if (!id || !name) continue;
            await counter.add(
                x.stream(
                    kind,
                    id,
                    name,
                    text(o, "stream_icon"),
                    names.get(text(o, "category_id") ?? "") ?? "",
                    text(o, "epg_channel_id"),
                    text(o, "is_adult") === "1",
                    text(o, "container_extension"),
                    core.added(text(o, "added")),
                    core.year([text(o, "year"), text(o, "releaseDate"), text(o, "release_date")]),
                    core.rating(text(o, "rating"), text(o, "rating_5based")),
                    order++,
                ),
                // Xtream's own catch-up flag (playlist.md › Catch-up): replayed through the server's timeshift link.
                kind === "live" && xtreamDays(o.tv_archive, o.tv_archive_duration) > 0
                    ? { catchUpDays: xtreamDays(o.tv_archive, o.tv_archive_duration), catchUp: "xc\n" }
                    : undefined,
            );
        }
        await counter.flush();
    };
    await streams("live", "get_live_streams", "get_live_categories", "live", 2);
    await streams("movie", "get_vod_streams", "get_vod_categories", "movies", 3);

    events.onStep({ id: "shows", number: 4, count: COUNT, progress: null });
    const showCats = await categories("get_series_categories", "shows", 4);
    for (const o of await get("get_series", "shows", 4)) {
        const id = text(o, "series_id");
        const name = text(o, "name")?.trim();
        if (!id || !name) continue;
        await counter.add(
            x.stream(
                "series",
                id,
                name,
                text(o, "cover"),
                showCats.get(text(o, "category_id") ?? "") ?? "",
                null,
                false,
                null,
                core.added(text(o, "last_modified")),
                core.year([text(o, "year"), text(o, "releaseDate"), text(o, "release_date")]),
                core.rating(text(o, "rating"), text(o, "rating_5based")),
                order++,
            ),
        );
    }
    await counter.flush();
    if (counter.live + counter.movies + counter.shows === 0) throw new LoadError("empty", "The server sent no streams");
    return { live: counter.live, movies: counter.movies, shows: counter.shows, guideUrl: x.guideUrl, timezone: xtreamTimezone(account) };
}

/** The show id of a stand-in row (an Xtream show before its episodes are read), or null. */
export const standInId = (url: string): string | null => (url.startsWith("xtream-series:") ? url.slice("xtream-series:".length) : null);

/** A show's episodes from `get_series_info`, in season and episode order. */
export async function loadEpisodes(fetch: Fetch, login: PlaylistLogin, show: Item): Promise<Item[]> {
    const seriesId = standInId(show.url);
    if (login.kind !== "xtream" || !seriesId) return [];
    const x = new Xtream(login.server, login.username, login.password);
    const answer = (await readJson(fetch, x.seriesInfoUrl(seriesId), { redact: (t) => x.redact(t) })) as Json;
    const seasons = answer?.episodes;
    const lists: unknown[] = Array.isArray(seasons) ? [seasons] : seasons && typeof seasons === "object" ? Object.values(seasons) : [];
    const out: Item[] = [];
    let order = 0;
    for (const list of lists) {
        if (!Array.isArray(list)) continue;
        for (const o of list as Json[]) {
            const id = text(o, "id");
            if (!id) continue;
            const info = (o.info ?? {}) as Json;
            out.push(
                toItem(
                    x.episode(
                        show.showName ?? show.name,
                        show.group,
                        text(info, "movie_image") ?? show.logo,
                        id,
                        text(o, "title"),
                        Number(text(o, "season")) || 1,
                        Number(text(o, "episode_num")) || 1,
                        text(o, "container_extension"),
                        order++,
                    ),
                ),
            );
        }
    }
    return out.sort((a, b) => a.season - b.season || a.episode - b.episode);
}
