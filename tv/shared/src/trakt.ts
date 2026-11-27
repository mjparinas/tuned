// Trakt for the TV apps, as core/Trakt.kt and data/TraktAccount.kt do it on Android (docs/decisions/trakt.md): signing
// in with a code typed at trakt.tv/activate, scrobbling movies and episodes as they play, and reading the watched
// history for an import. The app's id is TRAKT_CLIENT_ID, built in at build time and never typed in; Trakt issues no
// secret, so every sign-in body carries the id alone. Tokens are kept in the app's store under `trakt.token` and never
// logged. Shaped like `@tuned/shared/simkl`, so both apps use the two alike. `@tuned/shared/trakt`, with no tv/core.
import type { Fetch, KeyValueStore, Log } from "./platform.ts";
import type { PollAnswer, ScrobbleAnswer, ScrobbleMedia, SignInCode, Watched } from "./simkl.ts";
import { tmdbQuery } from "./tmdb.ts";

export type { PollAnswer, ScrobbleAnswer, ScrobbleMedia, SignInCode, Watched } from "./simkl.ts";

export const TRAKT_API = "https://api.trakt.tv";
const API_VERSION = "2";
const TOKEN_KEY = "trakt.token";
const SCROBBLE_KEY = "trakt.scrobble";
const DAY = 24 * 3600_000;
const SLOW_DOWN_MS = 1_000;
/** Films per page of `/sync/watched/movies` (Trakt's usual maximum). */
export const WATCHED_PAGE = 250;
/** Shows per page of `/sync/watched/shows?extended=progress` (Trakt caps progress pages at 100). */
export const WATCHED_PROGRESS_PAGE = 100;
/** Pages read at most, so a history that never ends can't hold the screen forever. */
const MAX_PAGES = 200;

/** Trakt counts a play as watched from 80%; under that, a stop only saves the spot. */
export const WATCHED_PERCENT = 80;
/** Trakt turns down a pause or stop under 1% ("progress_too_low"), and there is nothing to save there anyway. */
export const MIN_SPOT_PERCENT = 1;

export interface TraktDeps {
    readonly fetch: Fetch;
    readonly store: KeyValueStore;
    /** TRAKT_CLIENT_ID; empty hides Trakt. */
    readonly clientId: string;
    readonly log?: Log;
    readonly now?: () => number;
}

/** A sign-in as Android keeps it, and as the account's backup carries it (`copy.trakt`), so one sign-in covers every device. */
export interface TraktSession {
    readonly access: string;
    readonly refresh: string;
    /** When the access token runs out, in ms since 1970. */
    readonly expiresAt: number;
    readonly username: string | null;
}

type Row = Record<string, unknown>;
const obj = (v: unknown): Row | null => (v && typeof v === "object" && !Array.isArray(v) ? (v as Row) : null);
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | null => (typeof v === "string" && v ? v : null);
const int = (v: unknown): number | null => (typeof v === "number" && Number.isInteger(v) ? v : null);

/** How far through, as Trakt wants it: 0 to 100, to two places. */
export function traktProgress(positionMs: number, durationMs: number): number {
    if (!(durationMs > 0)) return 0;
    const at = Math.min(Math.max(positionMs, 0), durationMs);
    return Math.round(((at * 100) / durationMs) * 100) / 100;
}

/** Android's poll answers by HTTP status: 400 still waiting, 429 slow down, 404 and 410 ran out, 409 and 418 refused. */
export function pollAnswer(status: number): "waiting" | "slow down" | "denied" | "expired" | "signed in" {
    if (status === 200) return "signed in";
    if (status === 429) return "slow down";
    if (status === 404 || status === 410) return "expired";
    if (status === 409 || status === 418) return "denied";
    return "waiting";
}

/** The account's backup text (`{copy: {trakt, traktScrobble}}`): the sign-in, and whether scrobbling is on, when it has them. */
export function traktFromBackup(text: string | null): TraktSession | null {
    return readSession(backupCopy(text)?.trakt);
}

/** Whether the backup has scrobbling on or off, or null when it doesn't say. */
export function traktScrobbleFromBackup(text: string | null): boolean | null {
    const on = backupCopy(text)?.traktScrobble;
    return typeof on === "boolean" ? on : null;
}

function backupCopy(text: string | null): Row | null {
    try {
        const whole = text ? obj(JSON.parse(text)) : null;
        return obj(whole?.copy) ?? whole;
    } catch {
        return null;
    }
}

function readSession(v: unknown): TraktSession | null {
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

function entry(item: Row | null, at: number): Watched | null {
    const title = str(item?.title);
    if (!title) return null;
    const year = int(item?.year);
    const tmdbId = int(obj(item?.ids)?.tmdb);
    return { title, ...(year !== null ? { year } : {}), ...(tmdbId !== null ? { tmdbId } : {}), watchedAt: at };
}

/** One page of `/sync/watched/movies` as rows, and how many rows the page had (to know whether another follows). */
export function readWatchedMovies(text: string, now: number): { rows: Watched[]; count: number } | null {
    const list = parseList(text);
    if (!list) return null;
    const rows: Watched[] = [];
    for (const raw of list) {
        const item = obj(raw);
        const row = entry(obj(item?.movie), watchedAt(item?.last_watched_at, now));
        if (row) rows.push(row);
    }
    return { rows, count: list.length };
}

/** One page of `/sync/watched/shows` as one row per watched episode. */
export function readWatchedShows(text: string, now: number): { rows: Watched[]; count: number } | null {
    const list = parseList(text);
    if (!list) return null;
    const rows: Watched[] = [];
    for (const raw of list) {
        const item = obj(raw);
        const show = obj(item?.show);
        for (const s of arr(item?.seasons)) {
            const season = int(obj(s)?.number);
            if (season === null) continue;
            for (const e of arr(obj(s)?.episodes)) {
                const episode = int(obj(e)?.number);
                if (episode === null) continue;
                const row = entry(show, watchedAt(obj(e)?.last_watched_at ?? item?.last_watched_at, now));
                if (row) rows.push({ ...row, season, episode });
            }
        }
    }
    return { rows, count: list.length };
}

function parseList(text: string): unknown[] | null {
    try {
        const v: unknown = JSON.parse(text || "[]");
        return Array.isArray(v) ? v : null;
    } catch {
        return null;
    }
}

function parse(text: string): Row | null {
    try {
        return obj(JSON.parse(text));
    } catch {
        return null;
    }
}

/** What Trakt is told: title and year (and TMDB's id when known) for a movie, the show's with season and number for an episode. */
export function scrobbleBody(media: ScrobbleMedia, progress: number): Row {
    const name = tmdbQuery(media.type === "movie" ? media.title : media.show);
    const year = media.year ?? name.year;
    const ids = media.tmdbId ? { ids: { tmdb: media.tmdbId } } : {};
    const titled = { title: name.title, ...(year ? { year } : {}), ...ids };
    return media.type === "movie"
        ? { movie: titled, progress }
        : { show: titled, episode: { season: media.season, number: media.episode }, progress };
}

type Answer = { status: number; text: string } | null;

export class Trakt {
    private readonly deps: TraktDeps;
    private readonly now: () => number;
    private session: TraktSession | null | undefined;
    private refreshing: Promise<string | null> | null = null;

    constructor(deps: TraktDeps) {
        this.deps = deps;
        this.now = deps.now ?? Date.now;
    }

    /** Whether this build has Trakt's id at all. */
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
     * Trakt once covers every device. One missing from the backup never signs this one out.
     */
    async adopt(synced: TraktSession | null): Promise<boolean> {
        if (!synced) return false;
        const mine = await this.load();
        if (mine && mine.expiresAt >= synced.expiresAt) return false;
        await this.save(synced);
        return true;
    }

    async startSignIn(): Promise<SignInCode | null> {
        if (!this.available) return null;
        const answer = await this.post("/oauth/device/code", { client_id: this.deps.clientId }, null);
        if (answer?.status !== 200) return null;
        const body = parse(answer.text);
        const userCode = str(body?.user_code);
        const deviceCode = str(body?.device_code);
        const url = str(body?.verification_url);
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

    /** Asks once whether the code was typed. The screen waits `intervalMs` between asks, a second longer after "slow down". */
    async pollSignIn(code: SignInCode): Promise<PollAnswer> {
        if (this.now() >= code.expiresAt) return "expired";
        const answer = await this.post("/oauth/device/token", { code: code.deviceCode, client_id: this.deps.clientId }, null);
        if (!answer) return "waiting";
        const poll = pollAnswer(answer.status);
        if (poll !== "signed in") return poll;
        const session = await this.fromTokens(answer.text, null);
        return session ? { signedIn: true, user: session.username } : "denied";
    }

    /** How much longer to wait after "slow down", as Android does. */
    static slowDown(intervalMs: number): number {
        return intervalMs + SLOW_DOWN_MS;
    }

    /** Forgets the sign-in here and tells Trakt to drop the token. */
    async signOut(): Promise<void> {
        const s = await this.load();
        await this.clear();
        if (s) await this.post("/oauth/revoke", { token: s.access, client_id: this.deps.clientId }, null);
    }

    /** Forgets the sign-in on this device only (signing out of Tuned), so the other devices on the backup stay in. */
    async forget(): Promise<void> {
        await this.clear();
        await this.deps.store.remove(SCROBBLE_KEY);
    }

    /**
     * Tells Trakt a movie or episode started, paused or stopped at `progress` percent. Nothing is sent when scrobbling
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
            const answer = await this.post(`/scrobble/${kind}`, scrobbleBody(media, progress), token);
            if (!answer) return "offline";
            if (answer.status === 401) return "signed out";
            // 409: the same scrobble twice in a row, already recorded.
            if ((answer.status < 200 || answer.status > 299) && answer.status !== 409) {
                this.deps.log?.("Trakt", `Trakt didn't take the ${kind} at ${progress}% (HTTP ${answer.status})`);
                return "offline";
            }
            return "ok";
        } catch (e) {
            this.deps.log?.("Trakt", `Scrobble failed: ${e instanceof Error ? e.message : String(e)}`);
            return "offline";
        }
    }

    /**
     * Every watched movie and episode, page by page, for an import read once on a tap. Null when signed out or Trakt
     * couldn't be reached; an empty list when there are none.
     */
    async watchedHistory(): Promise<Watched[] | null> {
        try {
            const token = await this.token();
            if (!token) return null;
            const movies = await this.pages("/sync/watched/movies?", WATCHED_PAGE, token, readWatchedMovies);
            if (!movies) return null;
            const shows = await this.pages("/sync/watched/shows?extended=progress&", WATCHED_PROGRESS_PAGE, token, readWatchedShows);
            if (!shows) return null;
            return [...movies, ...shows];
        } catch (e) {
            this.deps.log?.("Trakt", `Watched history failed: ${e instanceof Error ? e.message : String(e)}`);
            return null;
        }
    }

    private async pages(
        path: string,
        limit: number,
        token: string,
        read: (text: string, now: number) => { rows: Watched[]; count: number } | null,
    ): Promise<Watched[] | null> {
        const out: Watched[] = [];
        for (let page = 1; page <= MAX_PAGES; page++) {
            const answer = await this.get(`${path}page=${page}&limit=${limit}`, token);
            if (answer?.status !== 200) {
                if (answer) this.deps.log?.("Trakt", `Trakt's watched history answered ${answer.status}`);
                return null;
            }
            const got = read(answer.text, this.now());
            if (!got) {
                this.deps.log?.("Trakt", "Couldn't read Trakt's watched history");
                return null;
            }
            out.push(...got.rows);
            if (got.count < limit) break;
        }
        return out;
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

    private async renew(s: TraktSession): Promise<string | null> {
        const body = { refresh_token: s.refresh, client_id: this.deps.clientId, grant_type: "refresh_token" };
        const answer = await this.post("/oauth/token", body, null);
        if (answer?.status === 200) return (await this.fromTokens(answer.text, s.username))?.access ?? null;
        // Trakt no longer takes the account (revoked on trakt.tv): signed out here too.
        if (answer?.status === 400 || answer?.status === 401) await this.clear();
        return null;
    }

    private async fromTokens(text: string, username: string | null): Promise<TraktSession | null> {
        const body = parse(text);
        const access = str(body?.access_token);
        const refresh = str(body?.refresh_token);
        const expiresIn = typeof body?.expires_in === "number" ? body.expires_in : null;
        if (!access || !refresh || expiresIn === null) return null;
        // Trakt says when the token was made (seconds); the device's clock stands in when it doesn't.
        const createdAt = typeof body?.created_at === "number" ? body.created_at * 1000 : this.now();
        const session = { access, refresh, expiresAt: createdAt + expiresIn * 1000, username: username ?? (await this.username(access)) };
        await this.save(session);
        return session;
    }

    private async username(token: string): Promise<string | null> {
        const answer = await this.get("/users/settings", token);
        return answer?.status === 200 ? str(obj(parse(answer.text)?.user)?.username) : null;
    }

    private async load(): Promise<TraktSession | null> {
        if (this.session !== undefined) return this.session;
        try {
            const text = await this.deps.store.get(TOKEN_KEY);
            this.session = text ? readSession(JSON.parse(text)) : null;
        } catch {
            this.session = null;
        }
        return this.session;
    }

    private async save(s: TraktSession): Promise<void> {
        this.session = s;
        await this.deps.store.set(TOKEN_KEY, JSON.stringify(s));
    }

    private async clear(): Promise<void> {
        this.session = null;
        await this.deps.store.remove(TOKEN_KEY);
    }

    private post(path: string, body: Row, token: string | null): Promise<Answer> {
        return this.request("POST", path, token, JSON.stringify(body));
    }

    private get(path: string, token: string): Promise<Answer> {
        return this.request("GET", path, token);
    }

    /** The answer's status and text, or null when the network failed. Tokens and codes are never logged. */
    private async request(method: "GET" | "POST", path: string, token: string | null, body?: string): Promise<Answer> {
        const headers: Record<string, string> = { "trakt-api-version": API_VERSION, "trakt-api-key": this.deps.clientId };
        if (token) headers.Authorization = `Bearer ${token}`;
        if (body !== undefined) headers["Content-Type"] = "application/json";
        try {
            const response = await this.deps.fetch(`${TRAKT_API}${path}`, { method, headers, ...(body !== undefined ? { body } : {}) });
            return { status: response.status, text: await response.text() };
        } catch (e) {
            this.deps.log?.("Trakt", `Couldn't reach Trakt: ${e instanceof Error ? e.name : "error"}`);
            return null;
        }
    }
}
