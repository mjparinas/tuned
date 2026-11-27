// Xtream Codes servers, as core/XtreamUrls.kt and the Android client read them. Replaced by @tuned/shared's Xtream
// client when it lands (docs/decisions/vega.md).
import type { Http } from "./http";
import type { Category, Channel, Episode, Login, Programme, Title } from "./models";

export function normalizeServer(server: string): string {
    const trimmed = server.trim().replace(/\/+$/, "");
    return /^https?:\/\//i.test(trimmed) ? trimmed : `http://${trimmed}`;
}

const enc = encodeURIComponent;

export function accountUrl(server: string, username: string, password: string): string {
    return `${normalizeServer(server)}/player_api.php?username=${enc(username)}&password=${enc(password)}`;
}

/** The `.ts` link: the stick's player plays MPEG-TS straight from the link, the quickest start (docs/decisions/vega.md). */
export function liveUrl(server: string, username: string, password: string, streamId: number): string {
    return `${normalizeServer(server)}/live/${enc(username)}/${enc(password)}/${streamId}.ts`;
}

type XtreamLogin = Extract<Login, { kind: "xtream" }>;

/** A movie or an episode: `/movie/` or `/series/`, with the file's own extension (mp4, mkv), which the stick plays as it is. */
export function vodUrl(login: XtreamLogin, path: "movie" | "series", id: string | number, extension: unknown): string {
    const ext = typeof extension === "string" && /^[a-z0-9]{2,5}$/i.test(extension) ? extension : "mp4";
    return `${normalizeServer(login.server)}/${path}/${enc(login.username)}/${enc(login.password)}/${id}.${ext}`;
}

export function seriesInfoUrl(login: XtreamLogin, seriesId: number): string {
    return `${accountUrl(login.server, login.username, login.password)}&action=get_series_info&series_id=${seriesId}`;
}

/** A link from a provider, or null for anything that isn't a picture on the web. */
const picture = (value: unknown): string | null => (typeof value === "string" && /^https?:\/\//.test(value) ? value : null);

/** The year from `year`, a release date, or "(2019)" in the name. */
export function yearOf(...values: unknown[]): number | null {
    for (const v of values) {
        const m = /\b(19\d\d|20\d\d)\b/.exec(String(v ?? ""));
        if (m) return Number(m[1]);
    }
    return null;
}

/** A rating out of 10; a provider's 0 means none. */
export function ratingOf(value: unknown): number | null {
    const n = Number(value);
    return Number.isFinite(n) && n > 0 && n <= 10 ? Math.round(n * 10) / 10 : null;
}

/** A provider's "added" or "last_modified", in Unix seconds, as ms; undefined when it gives none. */
export function addedOf(value: unknown): number | undefined {
    const n = Number(value);
    return Number.isFinite(n) && n > 0 ? n * 1000 : undefined;
}

const text = (value: unknown): string => (typeof value === "string" || typeof value === "number" ? String(value).trim() : "");

/** Movies from `get_vod_streams`, in the server's order. */
export function parseMovies(json: unknown, login: XtreamLogin): Title[] {
    if (!Array.isArray(json)) return [];
    const out: Title[] = [];
    for (const s of json) {
        const id = Number(s?.stream_id);
        const name = text(s?.name);
        if (!Number.isFinite(id) || !name) continue;
        out.push({
            id: `m${id}`,
            kind: "movie",
            name,
            categoryId: s.category_id != null ? String(s.category_id) : "",
            poster: picture(s.stream_icon),
            year: yearOf(s.year, s.releaseDate, s.release_date, name),
            rating: ratingOf(s.rating),
            ...withAdded(s.added),
            url: vodUrl(login, "movie", id, s.container_extension),
        });
    }
    return out;
}

const withAdded = (value: unknown) => {
    const added = addedOf(value);
    return added ? { added } : {};
};

/** Shows from `get_series`; their episodes are read when one is opened. */
export function parseShows(json: unknown): Title[] {
    if (!Array.isArray(json)) return [];
    const out: Title[] = [];
    for (const s of json) {
        const id = Number(s?.series_id);
        const name = text(s?.name);
        if (!Number.isFinite(id) || !name) continue;
        const plot = text(s.plot);
        out.push({
            id: `s${id}`,
            kind: "show",
            name,
            categoryId: s.category_id != null ? String(s.category_id) : "",
            poster: picture(s.cover),
            year: yearOf(s.year, s.releaseDate, s.release_date, name),
            rating: ratingOf(s.rating),
            ...withAdded(s.last_modified),
            ...(plot ? { plot } : {}),
            seriesId: id,
        });
    }
    return out;
}

/**
 * The episode's own name: the provider's title with the show's name and "S01E02" taken off ("Show - S01E02 - The
 * Pilot" is "The Pilot"), or null when nothing is left, so the screen says "Episode 2" (as the Samsung app does).
 */
export function episodeName(title: string, show: string): string | null {
    const lower = show.trim().toLowerCase();
    let rest = title.trim();
    if (lower && rest.toLowerCase().startsWith(lower)) rest = rest.slice(lower.length);
    rest = rest
        .replace(/\bS\d{1,2}\s*E\d{1,3}\b/i, "")
        .replace(/^[\s\-–:|.]+|[\s\-–:|.]+$/g, "")
        .trim();
    return rest && !/^(episode|ep\.?)\s*\d+$/i.test(rest) ? rest : null;
}

/** A show's episodes from `get_series_info`, in season and episode order. */
export function parseEpisodes(json: any, login: XtreamLogin, show: string): Episode[] {
    const seasons = json?.episodes;
    const lists: unknown[] = Array.isArray(seasons) ? [seasons] : seasons && typeof seasons === "object" ? Object.values(seasons) : [];
    const out: Episode[] = [];
    for (const list of lists) {
        if (!Array.isArray(list)) continue;
        for (const e of list) {
            const id = text(e?.id);
            if (!id) continue;
            const plot = text(e.info?.plot);
            out.push({
                id: `e${id}`,
                season: Number(e.season) || 1,
                episode: Number(e.episode_num) || out.length + 1,
                name: episodeName(text(e.title), show) ?? (episodeName(text(e.info?.name), show) || null),
                url: vodUrl(login, "series", id, e.container_extension),
                ...(plot ? { plot } : {}),
            });
        }
    }
    return out.sort((a, b) => a.season - b.season || a.episode - b.episode);
}

/** A login pasted as a get.php or player_api.php link, as the phone page and the Android form read it. */
export function fromLink(text: string): Extract<Login, { kind: "xtream" }> | null {
    try {
        const url = new URL(text.trim());
        const username = url.searchParams.get("username");
        const password = url.searchParams.get("password");
        if (!username || !password || !/\/(get|player_api)\.php$/i.test(url.pathname)) return null;
        return { kind: "xtream", server: `${url.protocol}//${url.host}`, username, password };
    } catch {
        return null;
    }
}

export type AccountCheck = "ok" | "wrong login" | "expired" | "unreachable";

/** Whether the login works: `user_info.auth` is 1 for a good one, and `status` says when it ran out. */
export async function checkAccount(http: Http, login: Extract<Login, { kind: "xtream" }>): Promise<AccountCheck> {
    let answer;
    try {
        answer = await http.getJson(accountUrl(login.server, login.username, login.password));
    } catch {
        return "unreachable";
    }
    const info = answer.body?.user_info;
    if (answer.status === 401 || answer.status === 403 || (info && Number(info.auth) === 0)) return "wrong login";
    if (!info) return answer.status >= 200 && answer.status < 300 ? "wrong login" : "unreachable";
    if (typeof info.status === "string" && /expired|banned|disabled/i.test(info.status)) return "expired";
    return "ok";
}

export function parseCategories(json: unknown): Category[] {
    if (!Array.isArray(json)) return [];
    return json
        .filter((c) => c && c.category_id != null)
        .map((c) => ({ id: String(c.category_id), name: String(c.category_name ?? "").trim() || "Other" }));
}

/** Live channels in the server's order, numbered as the server numbers them (or by position). */
export function parseLiveStreams(json: unknown, login: Extract<Login, { kind: "xtream" }>): Channel[] {
    if (!Array.isArray(json)) return [];
    const out: Channel[] = [];
    for (const s of json) {
        const streamId = Number(s?.stream_id);
        if (!Number.isFinite(streamId)) continue;
        const number = Number(s.num);
        out.push({
            id: String(streamId),
            name: String(s.name ?? "").trim() || `Channel ${streamId}`,
            number: Number.isFinite(number) && number > 0 ? number : out.length + 1,
            categoryId: s.category_id != null ? String(s.category_id) : "",
            logo: typeof s.stream_icon === "string" && /^https?:\/\//.test(s.stream_icon) ? s.stream_icon : null,
            url: liveUrl(login.server, login.username, login.password, streamId),
            streamId,
            guideId: typeof s.epg_channel_id === "string" && s.epg_channel_id ? s.epg_channel_id : undefined,
        });
    }
    return out;
}

/** Base64 text from the guide; a provider that sends plain text gets it back as it is. */
function base64Text(value: unknown): string {
    const text = String(value ?? "");
    if (!/^[A-Za-z0-9+/=\s]+$/.test(text) || text.length % 4 !== 0) return text;
    try {
        const binary = globalThis.atob(text.replace(/\s/g, ""));
        const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
        return new TextDecoder().decode(bytes);
    } catch {
        return text;
    }
}

/** Now and next from `get_short_epg`: one small request, not a stream connection. */
export function parseShortEpg(json: any): Programme[] {
    const list = Array.isArray(json?.epg_listings) ? json.epg_listings : [];
    return list
        .map((p: any) => ({
            title: base64Text(p.title).trim(),
            start: Number(p.start_timestamp) * 1000,
            end: Number(p.stop_timestamp) * 1000,
        }))
        .filter((p: Programme) => p.title && Number.isFinite(p.start) && Number.isFinite(p.end) && p.end > p.start)
        .sort((a: Programme, b: Programme) => a.start - b.start);
}

export function shortEpgUrl(login: Extract<Login, { kind: "xtream" }>, streamId: number): string {
    return `${accountUrl(login.server, login.username, login.password)}&action=get_short_epg&stream_id=${streamId}&limit=3`;
}
