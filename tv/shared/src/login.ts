// A playlist login on a TV: an Xtream server, or an M3U link with its guide. Never logged, never shown whole: the
// password stays in the app's own storage and every message about a link goes through `redact` (security.md).

export type PlaylistLogin =
    | { readonly kind: "xtream"; readonly server: string; readonly username: string; readonly password: string; readonly name: string }
    | { readonly kind: "m3u"; readonly url: string; readonly guideUrl: string; readonly name: string };

const withScheme = (value: string): string => (/^[a-z][a-z0-9+.-]*:\/\//i.test(value) ? value : `http://${value}`);

/** A login the TV can use, or null when something it needs is missing. */
export function xtreamLogin(server: string, username: string, password: string, name = ""): PlaylistLogin | null {
    const s = server.trim().replace(/\/+$/, "");
    const u = username.trim();
    if (!s || !u || !password) return null;
    return { kind: "xtream", server: withScheme(s), username: u, password, name: name.trim() };
}

export function m3uLogin(url: string, guideUrl = "", name = ""): PlaylistLogin | null {
    const link = url.trim();
    if (!link) return null;
    return { kind: "m3u", url: withScheme(link), guideUrl: guideUrl.trim() ? withScheme(guideUrl.trim()) : "", name: name.trim() };
}

/**
 * A pasted `get.php` (or `player_api.php`) link is an Xtream login, as the app and the phone page read it; anything
 * else is a plain M3U link.
 */
export function fromLink(text: string, guideUrl = "", name = ""): PlaylistLogin | null {
    const value = text.trim();
    const match = /^([a-z][a-z0-9+.-]*:\/\/)?([^/?#]+)((?:\/[^/?#]*)*?)\/(get|player_api|xmltv|panel_api)\.php\?([^#]*)$/i.exec(value);
    if (match) {
        const query = new Map(
            match[5].split("&").map((pair) => {
                const [k, ...v] = pair.split("=");
                return [decode(k).toLowerCase(), decode(v.join("="))] as const;
            }),
        );
        const login = xtreamLogin(
            `${match[1] ?? "http://"}${match[2]}${match[3]}`,
            query.get("username") ?? "",
            query.get("password") ?? "",
            name,
        );
        if (login) return login;
    }
    return m3uLogin(value, guideUrl, name);
}

function decode(value: string): string {
    try {
        return decodeURIComponent(value.replace(/\+/g, " "));
    } catch {
        return value;
    }
}

/** The name a playlist shows under: its own, or the server's host. */
export function playlistName(login: PlaylistLogin): string {
    if (login.name) return login.name;
    const link = login.kind === "xtream" ? login.server : login.url;
    const host = /^[a-z][a-z0-9+.-]*:\/\/([^/?#:]+)/i.exec(link)?.[1];
    return host ?? "Playlist";
}

/** The same playlist, whatever its name: same server and user, or same link. */
export function samePlaylist(a: PlaylistLogin, b: PlaylistLogin): boolean {
    if (a.kind === "xtream" && b.kind === "xtream") return a.server.toLowerCase() === b.server.toLowerCase() && a.username === b.username;
    if (a.kind === "m3u" && b.kind === "m3u") return a.url === b.url;
    return false;
}

/**
 * The playlists in what a phone sealed for the TV: the phone page's `{ version, playlists }` or the app's whole sync
 * copy, whose `playlists` have the same fields. The one in use on the phone comes first. Files and MAC portals can't
 * be sent, so they're left out.
 */
export function fromHandoff(json: string): PlaylistLogin[] {
    let root: unknown;
    try {
        root = JSON.parse(json);
    } catch {
        return [];
    }
    const list = (root as { playlists?: unknown })?.playlists;
    if (!Array.isArray(list)) return [];
    const out: PlaylistLogin[] = [];
    for (const item of list) {
        const p = item as Record<string, unknown>;
        const str = (key: string) => (typeof p?.[key] === "string" ? (p[key] as string) : "");
        const login = str("server")
            ? xtreamLogin(str("server"), str("username"), str("password"), str("name"))
            : m3uLogin(str("playlistUrl"), str("guideUrl"), str("name"));
        if (login && !out.some((o) => samePlaylist(o, login))) out.push(login);
    }
    return out;
}

/** The one-time account code a phone app sealed in with the playlists ("accountLink"), or null. */
export function handoffAccountLink(json: string): string | null {
    try {
        const link = (JSON.parse(json) as { accountLink?: unknown })?.accountLink;
        return typeof link === "string" && link.trim() ? link : null;
    } catch {
        return null;
    }
}
