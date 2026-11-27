// Details from The Movie Database for the TV apps, as core/Tmdb.kt and MetadataRepository do it on Android: a summary,
// genres, running time, rating, backdrop and cast for a movie or show, and each episode's real name (Melvin,
// 2026-11-17: real names over "S1 E1"). The key is the app's own (TMDB_API_KEY, built in at build time, never typed
// in and never sent through Tuned's server). Answers are kept in the app's store for 30 days, so a page opened twice
// asks the network once (AGENTS.md: saved before streamed). `@tuned/shared/tmdb`, with no tv/core.
import type { Fetch, KeyValueStore } from "./platform.ts";

const API = "https://api.themoviedb.org/3";
export const IMAGES = "https://image.tmdb.org/t/p/w500";
export const BACKDROPS = "https://image.tmdb.org/t/p/w1280";
export const PROFILES = "https://image.tmdb.org/t/p/w185";
const KEEP_MS = 30 * 24 * 3600 * 1000;
/** A title TMDB doesn't know is asked about again after a day, not every time its page opens. */
const MISS_MS = 24 * 3600 * 1000;

export interface Person {
    id: number;
    name: string;
    character: string | null;
    photo: string | null;
}

export interface Details {
    tmdbId: number;
    title: string;
    overview: string | null;
    year: number | null;
    /** Out of 10, rounded to one place. */
    rating: number | null;
    /** In minutes; a show's usual episode length. */
    runtime: number | null;
    genres: string[];
    poster: string | null;
    backdrop: string | null;
    cast: Person[];
    seasons: number | null;
}

export interface EpisodeInfo {
    season: number;
    episode: number;
    name: string | null;
    overview: string | null;
    still: string | null;
    runtime: number | null;
}

const languagePrefix = /^\s*(?:[[(|]\s*[A-Za-z]{2,3}\s*[\])|]|[A-Za-z]{2,3}\s*[-:|])\s*/;
const yearInBrackets = /[([]\s*((?:19|20)\d{2})\s*[)\]]/;
const trailingYear = /[\s-]((?:19|20)\d{2})\s*$/;
const bracketed = /[[(][^\])]*[\])]/g;
const qualityTags = /\b(?:4K|UHD|FHD|HD|SD|HEVC|H\.?26[45]|1080p|720p|2160p|MULTI|VOSTFR|SUBBED|DUBBED|HDR10?|DV)\b/gi;

/** What to search for: the name without the provider's tags ("EN | Paper Boats (2025) 4K" is "Paper Boats", 2025). */
export function tmdbQuery(name: string): { title: string; year: number | null } {
    let text = name.replace(languagePrefix, "");
    const found = yearInBrackets.exec(text) ?? trailingYear.exec(text);
    const year = found ? Number(found[1]) : null;
    text = text.replace(yearInBrackets, " ").replace(trailingYear, " ").replace(bracketed, " ").replace(qualityTags, " ");
    const title = text
        .replace(/\s{2,}/g, " ")
        .trim()
        .replace(/^[\s\-|:.]+|[\s\-|:.]+$/g, "");
    return { title: title || name.trim(), year };
}

/** "1h 52m", "48m" or "2h"; null when unknown. */
export function runtimeText(minutes: number | null | undefined): string | null {
    if (!minutes || minutes <= 0) return null;
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    return h === 0 ? `${m}m` : m === 0 ? `${h}h` : `${h}h ${m}m`;
}

type Json = Record<string, any>;

const image = (base: string, path: unknown) => (typeof path === "string" && path ? `${base}${path}` : null);
const yearOf = (date: unknown) => {
    const m = /^(\d{4})/.exec(String(date ?? ""));
    return m ? Number(m[1]) : null;
};

export class Tmdb {
    private readonly fetch: Fetch;
    private readonly store: KeyValueStore;
    private readonly key: string;
    private readonly now: () => number;
    private readonly language: string;

    /**
     * `key` is a v3 API key (in the link) or a v4 read token (in a header); empty turns TMDB off. `language` is the
     * TV's ("fr-CA"), for names and summaries in it where TMDB has them.
     */
    constructor(fetch: Fetch, store: KeyValueStore, key: string, now: () => number = Date.now, language = "en-CA") {
        this.fetch = fetch;
        this.store = store;
        this.key = key;
        this.now = now;
        this.language = language;
    }

    get enabled(): boolean {
        return !!this.key.trim();
    }

    private url(path: string, params: Record<string, string> = {}): string {
        const all = { ...params, language: this.language };
        if (!this.key.trim().startsWith("eyJ")) Object.assign(all, { api_key: this.key.trim() });
        const query = Object.entries(all)
            .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
            .join("&");
        return `${API}${path}?${query}`;
    }

    private async get(url: string): Promise<Json> {
        const token = this.key.trim().startsWith("eyJ") ? { Authorization: `Bearer ${this.key.trim()}` } : undefined;
        const answer = await this.fetch(url, { headers: { Accept: "application/json", ...token } });
        if (!answer.ok) throw new Error(`TMDB ${answer.status}`);
        return (await answer.json()) as Json;
    }

    private async cached<T>(key: string, load: () => Promise<T | null>): Promise<T | null> {
        const storeKey = `tmdb.${key}`;
        try {
            const raw = await this.store.get(storeKey);
            if (raw) {
                const saved = JSON.parse(raw) as { at: number; value: T | null };
                if (this.now() - saved.at < (saved.value === null ? MISS_MS : KEEP_MS)) return saved.value;
            }
        } catch {
            // An unreadable saved answer is asked for again.
        }
        const value = await load();
        await this.store.set(storeKey, JSON.stringify({ at: this.now(), value })).catch(() => undefined);
        return value;
    }

    /**
     * A movie's or show's details by its name as the playlist gives it, or null when TMDB has no match. Throws when
     * TMDB can't be reached, so the screen can say so; a page still shows the playlist's own details either way.
     */
    async details(kind: "movie" | "show", name: string, year: number | null = null): Promise<Details | null> {
        if (!this.enabled) return null;
        const query = tmdbQuery(name);
        const wantYear = query.year ?? year;
        const tv = kind === "show";
        const cacheKey = `${kind}.${query.title.toLowerCase()}.${wantYear ?? ""}.${this.language}`;
        return this.cached(cacheKey, async () => {
            const params: Record<string, string> = { query: query.title, include_adult: "false" };
            if (wantYear) params[tv ? "first_air_date_year" : "primary_release_year"] = String(wantYear);
            let results = ((await this.get(this.url(`/search/${tv ? "tv" : "movie"}`, params))).results ?? []) as Json[];
            if (!results.length && wantYear) {
                delete params[tv ? "first_air_date_year" : "primary_release_year"];
                results = ((await this.get(this.url(`/search/${tv ? "tv" : "movie"}`, params))).results ?? []) as Json[];
            }
            const hit = results[0];
            if (!hit?.id) return null;
            const d = await this.get(this.url(`/${tv ? "tv" : "movie"}/${hit.id}`, { append_to_response: "credits" }));
            const runtime = tv ? Number((d.episode_run_time ?? [])[0]) : Number(d.runtime);
            const rating = Number(d.vote_average);
            return {
                tmdbId: Number(d.id),
                title: String(d.title ?? d.name ?? query.title),
                overview: typeof d.overview === "string" && d.overview.trim() ? d.overview.trim() : null,
                year: yearOf(d.release_date ?? d.first_air_date),
                rating: Number.isFinite(rating) && rating > 0 ? Math.round(rating * 10) / 10 : null,
                runtime: Number.isFinite(runtime) && runtime > 0 ? runtime : null,
                genres: ((d.genres ?? []) as Json[]).map((g) => String(g.name ?? "")).filter(Boolean),
                poster: image(IMAGES, d.poster_path),
                backdrop: image(BACKDROPS, d.backdrop_path),
                cast: ((d.credits?.cast ?? []) as Json[]).slice(0, 12).map((c) => ({
                    id: Number(c.id),
                    name: String(c.name ?? ""),
                    character: typeof c.character === "string" && c.character ? c.character : null,
                    photo: image(PROFILES, c.profile_path),
                })),
                seasons: tv && Number.isFinite(Number(d.number_of_seasons)) ? Number(d.number_of_seasons) : null,
            } satisfies Details;
        });
    }

    /** One season's episodes with their real names, summaries and stills. */
    async season(tmdbId: number, season: number): Promise<EpisodeInfo[]> {
        if (!this.enabled) return [];
        const list = await this.cached(`season.${tmdbId}.${season}.${this.language}`, async () => {
            const d = await this.get(this.url(`/tv/${tmdbId}/season/${season}`));
            return ((d.episodes ?? []) as Json[]).map((e) => ({
                season,
                episode: Number(e.episode_number),
                name: typeof e.name === "string" && e.name.trim() && !/^episode \d+$/i.test(e.name.trim()) ? e.name.trim() : null,
                overview: typeof e.overview === "string" && e.overview.trim() ? e.overview.trim() : null,
                still: image("https://image.tmdb.org/t/p/w300", e.still_path),
                runtime: Number(e.runtime) > 0 ? Number(e.runtime) : null,
            }));
        });
        return list ?? [];
    }
}
