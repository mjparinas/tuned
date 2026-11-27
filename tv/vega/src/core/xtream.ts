// Xtream Codes servers, as core/XtreamUrls.kt and the Android client read them. Replaced by @tuned/shared's Xtream
// client when it lands (docs/decisions/vega.md).
import type { Http } from "./http";
import type { Category, Channel, Login, Programme } from "./models";

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
