// Simkl for the TV apps, as core/Simkl.kt, data/SimklAccount.kt and data/SimklHistory.kt do it on Android
// (docs/decisions/simkl.md): signing in with a code typed at simkl.com/pin, scrobbling movies and episodes as they play,
// and reading the watched history for an import. The app's id is SIMKL_CLIENT_ID, built in at build time and never typed
// in; the device code needs no secret. Tokens are kept in the app's store under `simkl.token` and never logged. Shaped
// like `@tuned/shared/trakt`, so both apps use the two alike. `@tuned/shared/simkl`, with no tv/core.
import type { Fetch, KeyValueStore, Log } from "./platform.ts";
import { tmdbQuery } from "./tmdb.ts";

export const SIMKL_API = "https://api.simkl.com";
const APP_NAME = "tuned";
const SCOPE = "media:read media:write";
const DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
const TOKEN_KEY = "simkl.token";
const SCROBBLE_KEY = "simkl.scrobble";
const DAY = 24 * 3600_000;
const SLOW_DOWN_MS = 5_000;

/** Simkl counts a play as watched from 80%; under that, a stop only saves the spot. */
export const WATCHED_PERCENT = 80;
/** A pause or stop under 1% isn't sent: there is nothing to save. */
export const MIN_SPOT_PERCENT = 1;

export interface SimklDeps {
    readonly fetch: Fetch;
    readonly store: KeyValueStore;
    /** SIMKL_CLIENT_ID; empty hides Simkl. */
    readonly clientId: string;
    /** The app's version, sent as Simkl asks apps to. */
    readonly appVersion?: string;
    readonly log?: Log;
    readonly now?: () => number;
}

/** A sign-in as Android keeps it, and as the account's backup carries it (`copy.simkl`), so one sign-in covers every device. */
export interface SimklSession {
    readonly access: string;
    readonly refresh: string;
    /** When the access token runs out, in ms since 1970. */
    readonly expiresAt: number;
    readonly username: string | null;
}

/** Step one of signing in: the code to show and where to type it. */
export interface SignInCode {
    readonly userCode: string;
    readonly verificationUrl: string;
    readonly expiresAt: number;
    readonly intervalMs: number;
    readonly deviceCode: string;
}

/**
 * The answer to one poll. Simkl never says no: walking away and refusing both end as "expired". "denied" is Simkl
 * turning the app itself away (a bad id).
 */
export type PollAnswer = "waiting" | "slow down" | "denied" | "expired" | { readonly signedIn: true; readonly user: string | null };

/** What is playing. Names are cleaned of the provider's tags here, as for TMDB ("EN | Paper Boats (2025) 4K"). */
export type ScrobbleMedia =
    | { readonly type: "movie"; readonly title: string; readonly year?: number | null; readonly tmdbId?: number | null }
    | {
          readonly type: "episode";
          readonly show: string;
          readonly year?: number | null;
          readonly season: number;
          readonly episode: number;
          readonly tmdbId?: number | null;
      };

export type ScrobbleAnswer = "ok" | "offline" | "signed out";

/** A movie, or one episode of a show, from the watched history. */
export interface Watched {
    readonly tmdbId?: number;
    readonly title: string;
    readonly year?: number;
    readonly season?: number;
    readonly episode?: number;
    /** ms since 1970. */
    readonly watchedAt: number;
}

type Row = Record<string, unknown>;
const obj = (v: unknown): Row | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Row) : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const int = (v: unknown): number | null => {
    const n = typeof v === "string" && /^\d+$/.test(v) ? Number(v) : v;
    return typeof n === "number" && Number.isInteger(n) ? n : null;
};

/** How far through, as Simkl wants it: 0 to 100, to two places. */
export function simklProgress(positionMs: number, durationMs: number): number {
    if (!(durationMs > 0)) return 0;
    const at = Math.min(Math.max(positionMs, 0), durationMs);
    return Math.round(((at * 100) / durationMs) * 100) / 100;
}

/** A session from the account's backup text (`{copy: {simkl}}`), or null when it has none. */
export function simklFromBackup(text: string | null): SimklSession | null {
    let whole: Row | null = null;
    try {
        whole = text ? obj(JSON.parse(text)) : null;
    } catch {
        return null;
    }
    const copy = obj(whole?.copy) ?? whole;
    return readSession(copy?.simkl);
}

/** Whether the account's backup has Simkl scrobbling on or off (`copy.simklScrobble`), or null when it doesn't say. */
export function simklScrobbleFromBackup(text: string | null): boolean | null {
    let whole: Row | null = null;
    try {
        whole = text ? obj(JSON.parse(text)) : null;
    } catch {
        return null;
    }
    const on = (obj(whole?.copy) ?? whole)?.simklScrobble;
    return typeof on === "boolean" ? on : null;
}

function readSession(v: unknown): SimklSession | null {
    const s = obj(v);
    const access = str(s?.access);
    const refresh = str(s?.refresh);
    const expiresAt = typeof s?.expiresAt === "number" ? s.expiresAt : null;
    if (!access || !refresh || expiresAt === null) return null;
    return { access, refresh, expiresAt, username: str(s?.username) };
}

function watchedAt(text: unknown, fallback: number): number {
    const at = typeof text === "string" ? Date.parse(text) : NaN;
    return Number.isFinite(at) ? at : fallback;
}

/** Simkl's `/sync/all-items` answer as rows: completed movies, and every watched episode of shows and anime. */
export function readHistory(text: string, now: number): Watched[] {
    let root: Row | null;
    try {
        root = obj(JSON.parse(text || "{}"));
    } catch {
        return [];
    }
    const out: Watched[] = [];
    for (const key of ["movies", "shows", "anime"]) {
        for (const raw of arr(root?.[key])) {
            const item = obj(raw);
            if (!item) continue;
            const movie = obj(item.movie);
            if (movie) {
                const title = str(movie.title);
                if (!title) continue;
                out.push(entry(title, int(movie.year), int(obj(movie.ids)?.tmdb), watchedAt(item.last_watched_at, now)));
                continue;
            }
            const show = obj(item.show) ?? obj(item.anime);
            const title = str(show?.title);
            if (!show || !title) continue;
            const year = int(show.year);
            const tmdbId = int(obj(show.ids)?.tmdb);
            for (const s of arr(item.seasons)) {
                const season = obj(s);
                if (!season) continue;
                for (const e of arr(season.episodes)) {
                    const ep = obj(e);
                    if (!ep) continue;
                    // Anime is numbered by TVDB's seasons in playlists, so Simkl's TVDB numbers win where it maps one.
                    const tvdb = obj(ep.tvdb);
                    const seasonNo = int(tvdb?.season) ?? int(season.number);
                    const number = int(tvdb?.episode) ?? int(ep.number);
                    if (seasonNo === null || number === null) continue;
                    out.push({
                        ...entry(title, year, tmdbId, watchedAt(ep.watched_at ?? item.last_watched_at, now)),
                        season: seasonNo,
                        episode: number,
                    });
                }
            }
        }
    }
    return out;
}

function entry(title: string, year: number | null, tmdbId: number | null, at: number): Watched {
    return { title, ...(year !== null ? { year } : {}), ...(tmdbId !== null ? { tmdbId } : {}), watchedAt: at };
}

export class Simkl {
    private readonly now: () => number;
    private session: SimklSession | null | undefined;
    private refreshing: Promise<string | null> | null = null;

    private readonly deps: SimklDeps;

    constructor(deps: SimklDeps) {
        this.deps = deps;
        this.now = deps.now ?? Date.now;
    }

    /** Whether this build has Simkl's id at all. */
    get available(): boolean {
        return !!this.deps.clientId;
    }

    async state(): Promise<{ signedIn: boolean; user: string | null }> {
        const s = await this.load();
        return { signedIn: !!s, user: s?.username ?? null };
    }

    /** Settings' Scrobble what you watch: on unless turned off. */
    async scrobbleOn(): Promise<boolean> {
        return (await this.deps.store.get(SCROBBLE_KEY)) !== "off";
    }

    async setScrobble(on: boolean): Promise<void> {
        if (on) await this.deps.store.remove(SCROBBLE_KEY);
        else await this.deps.store.set(SCROBBLE_KEY, "off");
    }

    /**
     * Takes the sign-in another device keeps in the account's backup when it's newer than this one's, so connecting
     * Simkl once covers every device. One missing from the backup never signs this one out.
     */
    async adopt(synced: SimklSession | null): Promise<boolean> {
        if (!synced) return false;
        const mine = await this.load();
        if (mine && mine.expiresAt >= synced.expiresAt) return false;
        await this.save(synced);
        return true;
    }

    async startSignIn(): Promise<SignInCode | null> {
        if (!this.available) return null;
        const answer = await this.form("/oauth2/device", { client_id: this.deps.clientId, scope: SCOPE });
        if (answer?.status !== 200) return null;
        const body = parse(answer.text);
        const userCode = str(body?.user_code);
        const deviceCode = str(body?.device_code);
        const url = str(body?.verification_uri_complete) ?? str(body?.verification_uri);
        const expiresIn = int(body?.expires_in);
        if (!userCode || !deviceCode || !url || expiresIn === null) return null;
        return {
            userCode,
            deviceCode,
            verificationUrl: url,
            expiresAt: this.now() + expiresIn * 1000,
            intervalMs: Math.max(1, int(body?.interval) ?? 5) * 1000,
        };
    }

    /** Asks once whether the code was typed. The screen waits `intervalMs` between asks, 5 s longer after "slow down". */
    async pollSignIn(code: SignInCode): Promise<PollAnswer> {
        if (this.now() >= code.expiresAt) return "expired";
        const answer = await this.form("/oauth2/token", {
            grant_type: DEVICE_GRANT,
            client_id: this.deps.clientId,
            device_code: code.deviceCode,
        });
        if (!answer) return "waiting";
        if (answer.status === 200) {
            const session = await this.fromTokens(answer.text, null, null);
            if (!session) return "denied";
            return { signedIn: true, user: session.username };
        }
        if (answer.status === 401) return "denied";
        const error = str(parse(answer.text)?.error);
        if (error === "authorization_pending") return "waiting";
        if (error === "slow_down") return "slow down";
        if (error === "expired_token") return "expired";
        return "denied";
    }

    /** How much longer to wait after "slow down", as Android does. */
    static slowDown(intervalMs: number): number {
        return intervalMs + SLOW_DOWN_MS;
    }

    /** Forgets the sign-in here and tells Simkl to drop the token. */
    async signOut(): Promise<void> {
        const s = await this.load();
        await this.clear();
        if (s) await this.form("/oauth2/revoke", { client_id: this.deps.clientId, token: s.refresh });
    }

    /** Forgets the sign-in on this device only (signing out of Tuned), so the other devices on the backup stay in. */
    async forget(): Promise<void> {
        await this.clear();
        await this.deps.store.remove(SCROBBLE_KEY);
    }

    /**
     * Tells Simkl a movie or episode started, paused or stopped at `progress` percent. Nothing is sent when scrobbling
     * is off, or for a pause or stop under 1%. Never throws; a failure is logged and the next play or pause sends the
     * state again.
     */
    async scrobble(kind: "start" | "pause" | "stop", media: ScrobbleMedia, progress: number): Promise<ScrobbleAnswer> {
        try {
            if (!this.available || !(await this.load())) return "signed out";
            if (!(await this.scrobbleOn())) return "ok";
            if (kind !== "start" && progress < MIN_SPOT_PERCENT) return "ok";
            const token = await this.token();
            if (!token) return (await this.load()) ? "offline" : "signed out";
            const answer = await this.send("POST", `/scrobble/${kind}`, token, JSON.stringify(scrobbleBody(media, progress)));
            if (!answer) return "offline";
            if (answer.status === 401) return "signed out";
            // 409: Simkl already has this one as watched just now.
            if ((answer.status < 200 || answer.status > 299) && answer.status !== 409) {
                this.deps.log?.("Simkl", `Simkl didn't take the ${kind} at ${progress}% (HTTP ${answer.status})`);
                return "offline";
            }
            return "ok";
        } catch (e) {
            this.deps.log?.("Simkl", `Scrobble failed: ${e instanceof Error ? e.message : String(e)}`);
            return "offline";
        }
    }

    /**
     * Completed movies and every watched episode of shows and anime, for an import, read once on a tap (Simkl suspends
     * apps that poll it). Null when signed out or Simkl couldn't be reached; an anime list that fails is left out.
     */
    async watchedHistory(): Promise<Watched[] | null> {
        const token = await this.token();
        if (!token) return null;
        const movies = await this.history("/sync/all-items/movies/completed", token);
        if (movies === null) return null;
        const episodes = "episode_watched_at=yes&include_all_episodes=yes";
        const shows = await this.history(`/sync/all-items/shows?${episodes}&extended=full`, token);
        if (shows === null) return null;
        const anime = await this.history(`/sync/all-items/anime?${episodes}&extended=full_anime_seasons`, token);
        const now = this.now();
        return [...readHistory(movies, now), ...readHistory(shows, now), ...(anime ? readHistory(anime, now) : [])];
    }

    private async history(path: string, token: string): Promise<string | null> {
        const answer = await this.send("GET", path, token);
        if (answer?.status === 200) return answer.text;
        if (answer) this.deps.log?.("Simkl", `Simkl's watched history answered ${answer.status}`);
        return null;
    }

    /** A token good for at least a day, renewed first when it isn't; one renewal at a time. */
    private async token(): Promise<string | null> {
        const s = await this.load();
        if (!s) return null;
        if (this.now() < s.expiresAt - DAY) return s.access;
        if (!this.refreshing) {
            this.refreshing = this.renew(s).finally(() => {
                this.refreshing = null;
            });
        }
        return this.refreshing;
    }

    private async renew(s: SimklSession): Promise<string | null> {
        const answer = await this.form("/oauth2/token", {
            grant_type: "refresh_token",
            client_id: this.deps.clientId,
            refresh_token: s.refresh,
        });
        if (answer?.status === 200) return (await this.fromTokens(answer.text, s.username, s.refresh))?.access ?? null;
        if (answer?.status === 400 || answer?.status === 401) await this.clear();
        return null;
    }

    private async fromTokens(text: string, username: string | null, previousRefresh: string | null): Promise<SimklSession | null> {
        const body = parse(text);
        const access = str(body?.access_token);
        const refresh = str(body?.refresh_token) ?? previousRefresh;
        const expiresIn = typeof body?.expires_in === "number" ? body.expires_in : null;
        if (!access || !refresh || expiresIn === null) return null;
        const session = {
            access,
            refresh,
            expiresAt: this.now() + expiresIn * 1000,
            username: username ?? (await this.username(access)),
        };
        await this.save(session);
        return session;
    }

    private async username(token: string): Promise<string | null> {
        const answer = await this.send("GET", "/users/settings", token);
        return answer?.status === 200 ? str(obj(parse(answer.text)?.user)?.name) : null;
    }

    private async load(): Promise<SimklSession | null> {
        if (this.session !== undefined) return this.session;
        try {
            const text = await this.deps.store.get(TOKEN_KEY);
            this.session = text ? readSession(JSON.parse(text)) : null;
        } catch {
            this.session = null;
        }
        return this.session;
    }

    private async save(s: SimklSession): Promise<void> {
        this.session = s;
        await this.deps.store.set(TOKEN_KEY, JSON.stringify(s));
    }

    private async clear(): Promise<void> {
        this.session = null;
        await this.deps.store.remove(TOKEN_KEY);
    }

    private url(path: string): string {
        const join = path.includes("?") ? "&" : "?";
        const q = `client_id=${encodeURIComponent(this.deps.clientId)}&app-name=${APP_NAME}&app-version=${encodeURIComponent(
            this.deps.appVersion ?? "tv",
        )}`;
        return `${SIMKL_API}${path}${join}${q}`;
    }

    private form(path: string, fields: Record<string, string>): Promise<{ status: number; text: string } | null> {
        const body = Object.entries(fields)
            .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
            .join("&");
        return this.request(path, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body });
    }

    private send(method: "GET" | "POST", path: string, token: string, body?: string) {
        const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
        if (body !== undefined) headers["Content-Type"] = "application/json";
        return this.request(path, { method, headers, ...(body !== undefined ? { body } : {}) });
    }

    /** The answer's status and text, or null when the network failed. Tokens and codes are never logged. */
    private async request(
        path: string,
        init: { method: string; headers: Record<string, string>; body?: string },
    ): Promise<{ status: number; text: string } | null> {
        try {
            const response = await this.deps.fetch(this.url(path), init);
            return { status: response.status, text: await response.text() };
        } catch (e) {
            this.deps.log?.("Simkl", `Couldn't reach Simkl: ${e instanceof Error ? e.name : "error"}`);
            return null;
        }
    }
}

function parse(text: string): Row | null {
    try {
        return obj(JSON.parse(text));
    } catch {
        return null;
    }
}

/** What Simkl is told: title and year for a movie, the show's title and year with season and number for an episode. */
export function scrobbleBody(media: ScrobbleMedia, progress: number): Row {
    const name = tmdbQuery(media.type === "movie" ? media.title : media.show);
    const year = media.year ?? name.year;
    const ids = media.tmdbId ? { ids: { tmdb: media.tmdbId } } : {};
    const titled = { title: name.title, ...(year ? { year } : {}), ...ids };
    return media.type === "movie"
        ? { movie: titled, progress }
        : { show: titled, episode: { season: media.season, number: media.episode }, progress };
}
