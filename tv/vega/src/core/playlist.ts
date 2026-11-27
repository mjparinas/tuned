// Downloading a playlist, in numbered steps with real progress (AGENTS.md: waiting is never a mystery). Xtream servers
// answer player_api.php in JSON, which is smaller and quicker to read than their M3U: Live TV first, so the channels
// show as soon as they can, then movies and shows on their own (`downloadLibrary`). A link is read as M3U in slices,
// letting the screen draw between them so the stick never freezes, and gives its movies and shows in the same read.
import { HttpError, type Http } from "./http";
import { M3uReader } from "./m3u";
import type { Library, Lineup, Login } from "./models";
import { t } from "../ui/strings";
import { eta, type StepProgress } from "./progress";
import { accountUrl, readAccount, parseCategories, parseLiveStreams, parseMovies, parseShows } from "./xtream";

export class PlaylistError extends Error {
    constructor(public readonly reason: "wrong login" | "expired" | "unreachable" | "empty" | "not a playlist") {
        super(reason);
        this.name = "PlaylistError";
    }
}

const SLICE = 512 * 1024;
const breathe = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

type OnProgress = (p: StepProgress) => void;

function downloader(http: Http, onProgress: OnProgress, now: () => number) {
    return async (url: string, step: number, steps: number, label: string) => {
        const startedAt = now();
        onProgress({ step, steps, label, loaded: 0, total: null });
        try {
            return await http.text(url, {
                timeoutMs: 120_000,
                onProgress: ({ loaded, total }) =>
                    onProgress({ step, steps, label, loaded, total, eta: eta(loaded, total, startedAt, now()) }),
            });
        } catch (error) {
            if (error instanceof HttpError && (error.status === 401 || error.status === 403)) throw new PlaylistError("wrong login");
            throw new PlaylistError("unreachable");
        }
    };
}

/** Live TV; a link's movies and shows come with it, read from the same file. */
export async function downloadLineup(
    http: Http,
    login: Login,
    onProgress: OnProgress,
    now: () => number = Date.now,
): Promise<Lineup & { library?: Library }> {
    const download = downloader(http, onProgress, now);
    if (login.kind === "xtream") {
        onProgress({ step: 1, steps: 3, label: t.checkingLogin });
        const { check, timezone } = await readAccount(http, login);
        if (check !== "ok") throw new PlaylistError(check);
        const base = accountUrl(login.server, login.username, login.password);
        const categories = parseCategories(safeJson(await download(`${base}&action=get_live_categories`, 2, 3, t.downloadingCategories)));
        const channels = parseLiveStreams(safeJson(await download(`${base}&action=get_live_streams`, 3, 3, t.downloadingChannels)), login);
        if (!channels.length) throw new PlaylistError("empty");
        const used = new Set(channels.map((c) => c.categoryId));
        return { categories: categories.filter((c) => used.has(c.id)), channels, timezone, savedAt: now() };
    }

    const text = await download(login.playlistUrl, 1, 2, t.downloadingPlaylist);
    const reader = new M3uReader();
    for (let at = 0; at < text.length; at += SLICE) {
        reader.feed(text.slice(at, at + SLICE));
        onProgress({ step: 2, steps: 2, label: t.readingChannels(reader.channels.length) });
        await breathe();
    }
    const { categories, channels, movieCategories, showCategories, movies, shows } = reader.end();
    const nothing = !channels.length && !movies.length && !shows.length;
    if (!reader.sawHeader && nothing) throw new PlaylistError("not a playlist");
    if (nothing) throw new PlaylistError("empty");
    const savedAt = now();
    return {
        categories,
        channels,
        savedAt,
        ...(reader.guideUrl ? { guideUrl: reader.guideUrl } : {}),
        library: { movieCategories, showCategories, movies, shows, savedAt },
    };
}

/** An Xtream server's movies and shows, after Live TV: four numbered steps, each a JSON answer from player_api.php. */
export async function downloadLibrary(
    http: Http,
    login: Extract<Login, { kind: "xtream" }>,
    onProgress: OnProgress,
    now: () => number = Date.now,
): Promise<Library> {
    const download = downloader(http, onProgress, now);
    const base = accountUrl(login.server, login.username, login.password);
    const get = async (action: string, step: number, label: string) => safeJson(await download(`${base}&action=${action}`, step, 4, label));
    const movieCategories = parseCategories(await get("get_vod_categories", 1, t.downloadingMovieCategories));
    const movies = parseMovies(await get("get_vod_streams", 2, t.downloadingMovies), login);
    const showCategories = parseCategories(await get("get_series_categories", 3, t.downloadingShowCategories));
    const shows = parseShows(await get("get_series", 4, t.downloadingShows));
    const used = (titles: { categoryId: string }[]) => new Set(titles.map((t) => t.categoryId));
    const usedMovies = used(movies);
    const usedShows = used(shows);
    return {
        movieCategories: movieCategories.filter((c) => usedMovies.has(c.id)),
        showCategories: showCategories.filter((c) => usedShows.has(c.id)),
        movies,
        shows,
        savedAt: now(),
    };
}

function safeJson(text: string): unknown {
    try {
        return JSON.parse(text);
    } catch {
        return null;
    }
}

/** What the screen says for each failure, in words, with Try again beside it. */
export function failureText(error: unknown): string {
    const reason = error instanceof PlaylistError ? error.reason : "unreachable";
    switch (reason) {
        case "wrong login":
            return t.failed.wrongLogin;
        case "expired":
            return t.failed.expired;
        case "empty":
            return t.failed.empty;
        case "not a playlist":
            return t.failed.notPlaylist;
        default:
            return t.failed.unreachable;
    }
}

/** Refreshes are due after 12 hours, as on Android's default. */
export const isStale = (saved: { savedAt: number }, now = Date.now()) => now - saved.savedAt > 12 * 3600 * 1000;
