// The channel list's rules (docs/decisions/web.md › The channel list), kept free of the page so they run under
// `node --test`. Everything here runs in the person's browser: the playlist and guide come straight from the provider
// (or, where the provider blocks web pages, only the whole-playlist link is offered), and a channel opens in VLC or
// another player on the person's own device, which connects to the provider itself. Nothing passes through Tuned.

const enc = encodeURIComponent;

/** The text after the first comma that isn't inside an attribute's quotes: `#EXTINF:-1 tvg-name="a, b",Name`. */
function extinfName(line) {
    let quoted = false;
    for (let i = 0; i < line.length; i++) {
        const c = line[i];
        if (c === '"') quoted = !quoted;
        else if (c === "," && !quoted) return line.slice(i + 1).trim();
    }
    return "";
}

const attr = (line, name) => new RegExp(`${name}="([^"]*)"`, "i").exec(line)?.[1]?.trim() ?? "";

/** Every channel in an M3U playlist: name, group, logo, guide id and stream link, in the playlist's order. */
export function parseM3u(text) {
    const out = [];
    let pending = null;
    let group = "";
    for (const raw of String(text).split(/\r?\n/)) {
        const line = raw.trim();
        if (!line) continue;
        if (line.startsWith("#EXTINF")) {
            pending = {
                name: extinfName(line) || attr(line, "tvg-name"),
                group: attr(line, "group-title"),
                logo: attr(line, "tvg-logo"),
                epgId: attr(line, "tvg-id"),
                number: attr(line, "tvg-chno"),
            };
        } else if (line.startsWith("#EXTGRP:")) {
            group = line.slice(8).trim();
        } else if (!line.startsWith("#")) {
            const p = pending ?? { name: "", group: "", logo: "", epgId: "", number: "" };
            out.push({ ...p, name: p.name || line, group: p.group || group, url: line });
            pending = null;
            group = "";
        }
    }
    return out;
}

/** The guide link a playlist names in its header (`url-tvg` or `x-tvg-url`), or "". */
export function headerGuide(text) {
    const first = String(text).slice(0, 4096).split(/\r?\n/, 1)[0] ?? "";
    if (!first.startsWith("#EXTM3U")) return "";
    return (attr(first, "url-tvg") || attr(first, "x-tvg-url")).split(",")[0].trim();
}

/** An Xtream live stream's link, as the app builds it (core XtreamUrls): `.ts`, which every player opens. */
export const xtreamLiveUrl = (base, username, password, id, ext = "ts") =>
    `${base}/live/${enc(username)}/${enc(password)}/${enc(id)}.${ext}`;

/** The whole playlist's link for an Xtream login (get.php), as the app builds it. */
export const xtreamPlaylistUrl = (base, username, password) =>
    `${base}/get.php?username=${enc(username)}&password=${enc(password)}&type=m3u_plus&output=ts`;

/** The guide's link for an Xtream login (xmltv.php). */
export const xtreamGuideUrl = (base, username, password) => `${base}/xmltv.php?username=${enc(username)}&password=${enc(password)}`;

/** Xtream's `get_live_categories` and `get_live_streams` as channels, in the provider's order. */
export function xtreamChannels(categories, streams, base, username, password) {
    const names = new Map((Array.isArray(categories) ? categories : []).map((c) => [String(c.category_id), c.category_name ?? ""]));
    const list = (Array.isArray(streams) ? streams : []).filter((s) => s && s.stream_id != null);
    return list.map((s) => ({
        name: String(s.name ?? "").trim() || `Channel ${s.num ?? s.stream_id}`,
        group: names.get(String(s.category_id)) ?? "",
        logo: s.stream_icon ?? "",
        epgId: s.epg_channel_id ?? "",
        number: s.num != null ? String(s.num) : "",
        url: xtreamLiveUrl(base, username, password, s.stream_id),
    }));
}

/** The groups in the order they first appear, with how many channels each has. */
export function groupsOf(channels) {
    const counts = new Map();
    for (const c of channels) counts.set(c.group, (counts.get(c.group) ?? 0) + 1);
    return [...counts].map(([name, count]) => ({ name, count }));
}

const fold = (s) => String(s).normalize("NFKD").replace(/[̀-ͯ]/g, "").toLowerCase();

/** The channels in `group` ("" for all) whose name, or what's on now, has every typed word. */
export function filterChannels(channels, { group = "", query = "", nowTitle = () => "" } = {}) {
    const words = fold(query).split(/\s+/).filter(Boolean);
    return channels.filter((c) => {
        if (group && c.group !== group) return false;
        if (!words.length) return true;
        const hay = `${fold(c.name)} ${fold(nowTitle(c))}`;
        return words.every((w) => hay.includes(w));
    });
}

// ---------- Live TV's filters, as the app has them (core LiveTopic, ChannelQuality, ProgrammeGenre, Regions) ----------

/** Words as the app's full-text index sees them (core Search.normalize): no accents, lower case, letters and digits. */
export const normalize = (text) =>
    String(text ?? "")
        .normalize("NFD")
        .replace(/\p{Mn}+/gu, "")
        .toLowerCase()
        .replace(/[^\p{L}\p{N}]+/gu, " ")
        .trim();

const tokens = (text) => normalize(text).split(" ").filter(Boolean);

/** Whether `text` has one of `words`: `sport*` starts a word, a word with a space is a phrase (core LiveTopic.matches). */
export function hasWord(text, words) {
    const t = tokens(text);
    return words.some((word) => {
        if (word.includes(" ")) {
            const phrase = word.split(" ");
            for (let i = 0; i + phrase.length <= t.length; i++) if (phrase.every((w, j) => t[i + j] === w)) return true;
            return false;
        }
        return word.endsWith("*") ? t.some((x) => x.startsWith(word.slice(0, -1))) : t.includes(word);
    });
}

/** Live TV's kinds of channel, by name or category (core LiveTopic; Adult stays in the app, behind its PIN). */
export const TOPICS = [
    {
        key: "sports",
        label: "Sports",
        words: "sport* eurosport* supersport* skysport* btsport* foxsport* beinsport* tntsport* football* soccer* hockey* baseball* basketball* wrestling* boxing* cricket* rugby* tennis* golf* nascar* motogp* indycar* bundesliga* laliga* matchday* gameday* espn* dazn* fubo* fight* ufc* tsn* bein wwe aew nfl nba nhl mlb mls ncaa epl f1 mma willow"
            .split(" ")
            .concat(["la liga", "premier league", "serie a", "ligue 1", "formula 1", "match day", "game day"]),
    },
    { key: "ppv", label: "PPV and events", words: ["ppv*", "pay per view", "event*"] },
    {
        key: "news",
        label: "News",
        words: "news* euronews* cnn* msnbc cnbc* bloomberg* aljazeera* france24 dw cgtn i24* cp24 bfm* lci noticias* notizie* nachrichten* actualites haber*"
            .split(" ")
            .concat(["al jazeera", "france 24", "nhk world", "trt world"]),
    },
    {
        key: "kids",
        label: "Kids",
        words: "kid* child* cartoon* toon* teletoon* nick nickelodeon* nicktoons* cbeebies cbbc boomerang* babytv treehouse kika gulli jeunesse infantil* kinder* ninos"
            .split(" ")
            .concat(["disney junior", "disney jr", "disney channel", "baby tv", "pbs kids"]),
    },
    { key: "movies", label: "Movie channels", words: "movie* film* cine* kino* pelicula* hbo* showtime* starz* tcm mgm*".split(" ") },
    {
        key: "music",
        label: "Music",
        words: "music* musique* musik* mtv* vh1* vevo* cmt stingray* concert* karaoke* clubbing* 4music".split(" "),
    },
    {
        key: "documentary",
        label: "Documentaries",
        words: "docu* documenta* doku* docs discovery* natgeo* history* historia smithsonian* curiosity* planete*"
            .split(" ")
            .concat(["nat geo", "national geographic", "animal planet", "science channel"]),
    },
];

/** Picture quality from a word in the name (core ChannelQuality): whole words, so "hd" isn't "fhd". */
export const QUALITIES = [
    { key: "uhd", label: "4K", words: ["4k", "uhd", "2160p", "8k"] },
    { key: "fhd", label: "FHD", words: ["fhd", "1080p", "1080i", "fullhd", "full hd"] },
    { key: "hd", label: "HD", words: ["hd", "720p", "hdtv"] },
    { key: "sd", label: "SD", words: ["sd", "480p", "576p", "576i", "480i", "lq"] },
];

/** What's on, by the code the app stores (core ProgrammeGenre.code). */
export const GENRES = [
    { code: 1, label: "Sports" },
    { code: 2, label: "Movies" },
    { code: 3, label: "News" },
    { code: 4, label: "Kids" },
    { code: 5, label: "Documentaries" },
];

/** A match between two sides ("Leafs vs Canadiens"), sports when the guide gives no kind (core ProgrammeGenre.of). */
const versus = /\S\s+(vs\.?|v|@)\s+\S/i;
export const genreOf = (p) => (p?.genre > 0 ? p.genre : versus.test(p?.title ?? "") ? 1 : 0);

/** The app's names for its own tags (core Regions); any other tag is a country code the browser can name. */
const tagNames = {
    EN: "English",
    AR: "Arabic",
    KU: "Kurdish",
    EX: "Ex-Yugoslavia",
    LAT: "Latino",
    AFR: "Africa",
    ASIA: "Asia",
    EUR: "Europe",
    NORDIC: "Nordic",
    UK: "United Kingdom",
    US: "United States",
    KR: "Korea",
};
let regionNames = null;
export function regionName(tag) {
    const t = String(tag ?? "").toUpperCase();
    if (tagNames[t]) return tagNames[t];
    try {
        regionNames ??= new Intl.DisplayNames(["en-CA"], { type: "region" });
        const name = /^[A-Z]{2}$/.test(t) ? regionNames.of(t) : null;
        if (name && name !== t) return name;
    } catch {
        // An old browser: the tag itself.
    }
    return t;
}

/** The filters' empty state: every channel. */
export const NO_FILTERS = Object.freeze({ group: "", query: "", topic: "", quality: "", onNow: 0, country: "", withGuide: false });

/**
 * Live TV's filters together, as the app narrows its list: group, typed words (in the name or what's on now), kind
 * of channel, picture quality, kind of programme on now, country and Has guide. `nowOf(c)` is what's on now, or null.
 */
export function applyFilters(channels, f, nowOf = () => null) {
    const words = fold(f.query ?? "")
        .split(/\s+/)
        .filter(Boolean);
    const topic = TOPICS.find((t) => t.key === f.topic);
    const quality = QUALITIES.find((q) => q.key === f.quality);
    return channels.filter((c) => {
        if (f.group && c.group !== f.group) return false;
        if (f.country && c.region !== f.country) return false;
        if (topic && !hasWord(c.name, topic.words) && !hasWord(c.group, topic.words)) return false;
        if (quality && !hasWord(c.name, quality.words)) return false;
        const on = f.onNow || f.withGuide || words.length ? nowOf(c) : null;
        if (f.withGuide && !c.hasGuide) return false;
        if (f.onNow && genreOf(on) !== f.onNow) return false;
        if (!words.length) return true;
        const hay = `${fold(c.name)} ${fold(on?.title ?? "")}`;
        return words.every((w) => hay.includes(w));
    });
}

/** Which choices would find anything, so only those are offered (as the app's filter sheet does). */
export function offers(channels, nowOf = () => null) {
    const countries = new Map();
    const topics = new Set();
    const qualities = new Set();
    const genres = new Set();
    let guide = false;
    for (const c of channels) {
        if (c.region) countries.set(c.region, (countries.get(c.region) ?? 0) + 1);
        for (const t of TOPICS) if (!topics.has(t.key) && (hasWord(c.name, t.words) || hasWord(c.group, t.words))) topics.add(t.key);
        for (const q of QUALITIES) if (!qualities.has(q.key) && hasWord(c.name, q.words)) qualities.add(q.key);
        const g = genreOf(nowOf(c));
        if (g) genres.add(g);
        if (c.hasGuide) guide = true;
    }
    return {
        countries: [...countries]
            .map(([tag, count]) => ({ tag, name: regionName(tag), count }))
            .sort((a, b) => a.name.localeCompare(b.name, "en-CA")),
        topics: TOPICS.filter((t) => topics.has(t.key)),
        qualities: QUALITIES.filter((q) => qualities.has(q.key)),
        genres: GENRES.filter((g) => genres.has(g.code)),
        guide,
    };
}

// ---------- the guide (XMLTV) ----------

/** An XMLTV time, `20261120190000 +0000` (the offset optional, UTC then), in milliseconds, or NaN. */
export function xmltvTime(text) {
    const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?(?:\s*([+-]\d{2}):?(\d{2})?)?/.exec(String(text ?? "").trim());
    if (!m) return Number.NaN;
    const [, y, mo, d, h, mi, s = "00", oh = "+00", om = "00"] = m;
    const utc = Date.UTC(+y, +mo - 1, +d, +h, +mi, +s);
    const sign = oh.startsWith("-") ? -1 : 1;
    return utc - sign * (Math.abs(+oh) * 60 + +om) * 60_000;
}

const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };

/** XML text with its entities and CDATA undone. */
export function xmlText(s) {
    return String(s ?? "")
        .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
        .replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (all, e) => {
            if (e[0] === "#") {
                const code = e[1] === "x" || e[1] === "X" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
                return Number.isFinite(code) && code > 0 && code < 0x110000 ? String.fromCodePoint(code) : all;
            }
            return ENTITIES[e.toLowerCase()] ?? all;
        })
        .trim();
}

const xmlAttr = (tag, name) => {
    const m = new RegExp(`\\s${name}\\s*=\\s*("([^"]*)"|'([^']*)')`, "i").exec(tag);
    return m ? xmlText(m[2] ?? m[3]) : "";
};

/**
 * Reads an XMLTV guide a piece at a time, as it downloads, so a 100 MB guide never sits in memory whole. Keeps only
 * the programmes of the channels in `wanted` (their guide ids, matched without case) that overlap `from` to `to`.
 */
export class GuideReader {
    constructor(wanted, from, to) {
        this.wanted = new Map([...wanted].filter(Boolean).map((id) => [id.toLowerCase(), id]));
        this.from = from;
        this.to = to;
        this.rest = "";
        this.byChannel = new Map();
        this.count = 0;
    }

    /** One more piece of the file's text. */
    feed(chunk) {
        let text = this.rest + chunk;
        let at = 0;
        for (;;) {
            const start = text.indexOf("<programme", at);
            if (start < 0) {
                // Keep a tail that might be the start of "<programme" cut in two.
                at = Math.max(at, text.length - 16);
                break;
            }
            const end = text.indexOf("</programme>", start);
            const selfClosed = text.indexOf("/>", start);
            const open = text.indexOf(">", start);
            if (open < 0) {
                at = start;
                break;
            }
            if (selfClosed >= 0 && selfClosed + 1 === open) {
                at = open + 1;
                continue;
            }
            if (end < 0) {
                at = start;
                break;
            }
            this.take(text.slice(start, open + 1), text.slice(open + 1, end));
            at = end + 12;
        }
        this.rest = text.slice(at);
        text = null;
    }

    take(tag, inner) {
        const id = this.wanted.get(xmlAttr(tag, "channel").toLowerCase());
        if (!id) return;
        const start = xmltvTime(xmlAttr(tag, "start"));
        const stop = xmltvTime(xmlAttr(tag, "stop"));
        if (!Number.isFinite(start) || !Number.isFinite(stop) || stop <= this.from || start >= this.to) return;
        const title = xmlText(/<title[^>]*>([\s\S]*?)<\/title>/i.exec(inner)?.[1] ?? "");
        const desc = xmlText(/<desc[^>]*>([\s\S]*?)<\/desc>/i.exec(inner)?.[1] ?? "");
        let list = this.byChannel.get(id);
        if (!list) this.byChannel.set(id, (list = []));
        list.push({ start, stop, title, desc: desc.slice(0, 400) });
        this.count++;
    }

    /** The programmes kept, sorted by start time, one list per guide id as the playlist spells it. */
    done() {
        for (const list of this.byChannel.values()) {
            list.sort((a, b) => a.start - b.start);
            // A guide that lists the same programme twice (two sources joined) shows it once.
            for (let i = list.length - 1; i > 0; i--) if (list[i].start === list[i - 1].start) list.splice(i, 1);
        }
        return this.byChannel;
    }
}

/** What's on now and next on a channel's programmes, either null. */
export function nowNext(programmes, now) {
    if (!programmes?.length) return { now: null, next: null };
    const i = programmes.findIndex((p) => p.stop > now);
    if (i < 0) return { now: null, next: null };
    const first = programmes[i];
    if (first.start <= now) return { now: first, next: programmes[i + 1] ?? null };
    return { now: null, next: first };
}

/** How far through a programme `now` is, 0 to 1. */
export const progress = (p, now) => (p ? Math.min(1, Math.max(0, (now - p.start) / Math.max(1, p.stop - p.start))) : 0);

/** The guide grid's time slots: half hours from the last half hour before `now`, `hours` long. */
export function gridSlots(now, hours = 3) {
    const first = Math.floor(now / 1_800_000) * 1_800_000;
    return Array.from({ length: hours * 2 }, (_, i) => first + i * 1_800_000);
}

/** A programme's place in the grid, as fractions of the grid's width from `from` to `to`, or null when it's outside. */
export function gridPlace(p, from, to) {
    if (p.stop <= from || p.start >= to) return null;
    const span = to - from;
    const left = Math.max(0, (p.start - from) / span);
    const right = Math.min(1, (p.stop - from) / span);
    return { left, width: right - left };
}

// ---------- opening a channel in another player ----------

/** The device the page runs on, from its user agent, for which "Open in VLC" link it gets. */
export function platformOf(userAgent, maxTouchPoints = 0) {
    const ua = String(userAgent ?? "");
    if (/android/i.test(ua)) return "android";
    if (/iphone|ipad|ipod/i.test(ua) || (/macintosh/i.test(ua) && maxTouchPoints > 1)) return "ios";
    if (/macintosh|mac os x/i.test(ua)) return "mac";
    if (/windows/i.test(ua)) return "windows";
    return "other";
}

/**
 * The link that opens a stream straight in VLC, or null where VLC has no link of its own (Windows, Linux): there the
 * channel's .m3u file opens in VLC or the computer's usual player instead.
 *  - Android: an intent for VLC's app, which offers to install it when it's missing.
 *  - iPhone and iPad: VLC's x-callback link.
 *  - Mac: VLC's `vlc://` link.
 */
export function vlcLink(url, platform, title = "") {
    let parsed;
    try {
        parsed = new URL(url);
    } catch {
        return null;
    }
    if (platform === "android") {
        const scheme = parsed.protocol.slice(0, -1);
        const rest = url.slice(url.indexOf("://") + 3);
        const name = title ? `S.title=${enc(title)};` : "";
        return `intent://${rest}#Intent;scheme=${scheme};package=org.videolan.vlc;type=video/*;${name}end`;
    }
    if (platform === "ios") return `vlc-x-callback://x-callback-url/stream?url=${enc(url)}`;
    if (platform === "mac") return `vlc://${url}`;
    return null;
}

/** An M3U playlist of these channels, which any player opens: one channel's file, or the whole list's. */
export function m3uFor(channels) {
    const clean = (s) =>
        String(s ?? "")
            .replace(/[\r\n"]+/g, " ")
            .trim();
    const lines = ["#EXTM3U"];
    for (const c of channels) {
        const attrs = [
            c.epgId ? ` tvg-id="${clean(c.epgId)}"` : "",
            c.logo ? ` tvg-logo="${clean(c.logo)}"` : "",
            c.group ? ` group-title="${clean(c.group)}"` : "",
        ].join("");
        lines.push(`#EXTINF:-1${attrs},${clean(c.name).replace(/,/g, " ")}`, clean(c.url));
    }
    return `${lines.join("\n")}\n`;
}

/** A file name for a channel's .m3u: its name without characters file systems refuse. */
export const fileName = (name) =>
    `${
        String(name ?? "")
            .replace(/[\\/:*?"<>|\u0000-\u001f]+/g, " ")
            .replace(/\s+/g, " ")
            .trim()
            .slice(0, 80) || "channel"
    }.m3u`;

// ---------- the app's copy (docs/decisions/web.md › The app's copy) ----------

/**
 * A link from the app's copy with the playlist's login put back where the app left `{login}`, `{user}` or `{pass}`,
 * encoded as the app builds Xtream links. Null when it needs a login the page doesn't have.
 */
export function fillLogin(link, login) {
    const text = String(link ?? "");
    if (!/\{(login|user|pass)\}/.test(text)) return text;
    const user = login?.username ?? "";
    const pass = login?.password ?? "";
    if (!user || !pass) return null;
    return text
        .replaceAll("{login}", `${enc(user)}/${enc(pass)}`)
        .replaceAll("{user}", enc(user))
        .replaceAll("{pass}", enc(pass));
}

/**
 * The app's copy (core ChannelShare's JSON) as the page's channels and guide: `{ channels, guide, savedAt }`, the
 * channels whose links need a login the page doesn't have left out. Null when it isn't a copy.
 */
export function fromAppCopy(copy, login) {
    if (copy?.v !== 1 || !Array.isArray(copy.channels)) return null;
    const channels = [];
    for (const c of copy.channels) {
        if (!Array.isArray(c)) continue;
        const [name, group, logo, epgId, number, link, region, adult] = c;
        // Adult channels stay in the app, where they sit behind the parental PIN.
        if (adult === 1) continue;
        const url = fillLogin(link, login);
        if (!url) continue;
        channels.push({
            region: typeof region === "string" ? region.toUpperCase() : "",
            name: String(name ?? "").trim() || "Channel",
            group: String(group ?? ""),
            logo: String(logo ?? ""),
            epgId: String(epgId ?? ""),
            number: Number(number) > 0 ? String(number) : "",
            url,
        });
    }
    const guide = new Map();
    for (const [id, list] of Object.entries(copy.guide ?? {})) {
        if (!Array.isArray(list)) continue;
        const shows = list
            .filter((p) => Array.isArray(p) && Number.isFinite(p[0]) && Number.isFinite(p[1]))
            .map(([start, stop, title, genre]) => ({
                start: start * 1000,
                stop: stop * 1000,
                title: String(title ?? ""),
                desc: "",
                genre: Number(genre) || 0,
            }))
            .sort((a, b) => a.start - b.start);
        if (shows.length) guide.set(id, shows);
    }
    return { channels, guide, savedAt: Number(copy.savedAt) || null };
}

/** How long ago, in words: "just now", "5 minutes ago", "3 hours ago", "2 days ago". */
export function ago(then, now = Date.now()) {
    const minutes = Math.max(0, Math.round((now - then) / 60_000));
    if (minutes < 1) return "just now";
    if (minutes < 60) return `${minutes} ${minutes === 1 ? "minute" : "minutes"} ago`;
    const hours = Math.round(minutes / 60);
    if (hours < 48) return `${hours} ${hours === 1 ? "hour" : "hours"} ago`;
    const days = Math.round(hours / 24);
    return `${days} days ago`;
}

/**
 * Where a channel's logo is read from. An https page can't show an http picture (browsers block or upgrade it), so
 * an http logo goes through Tuned's logo relay (`/api/account/logo`, pictures only: never a stream or a login).
 */
export function logoSrc(url, pageProtocol = "https:") {
    const u = String(url ?? "").trim();
    if (!/^https?:\/\//i.test(u)) return "";
    if (/^https:/i.test(u) || pageProtocol !== "https:") return u;
    return `/api/account/logo?u=${encodeURIComponent(u)}`;
}

/** The guide's times across the top: every half hour from `from` to `to`. */
export function ticks(from, to, step = 1_800_000) {
    const out = [];
    for (let t = Math.ceil(from / step) * step; t < to; t += step) out.push(t);
    return out;
}

/** The guide's span: from the half hour before `now` less `before`, to the last programme's end, at most `most` on. */
export function guideSpan(guide, now, before = 1_800_000, most = 26 * 3_600_000) {
    const from = Math.floor((now - before) / 1_800_000) * 1_800_000;
    let last = now + 3 * 3_600_000;
    for (const list of guide.values()) {
        const end = list.at(-1)?.stop ?? 0;
        if (end > last) last = end;
    }
    return { from, to: Math.min(Math.ceil(last / 1_800_000) * 1_800_000, from + most) };
}
