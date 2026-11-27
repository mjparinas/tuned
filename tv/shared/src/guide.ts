// The grid guide and catch-up for the TV apps (playlist.md › Grid guide, Catch-up): the TypeScript twin of
// core/GuideGrid.kt, core/CatchUp.kt and core/CatchUpTemplate.kt, with no tuned-core import, so Vega's bundle can take it
// by subpath. Times are epoch milliseconds; a screen only turns them into pixels.

export const MINUTE = 60_000;
export const SLOT = 30 * MINUTE;
export const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** No provider keeps more than a week worth having in the guide. */
export const MAX_CATCH_UP_DAYS = 7;
/** Days when a playlist names catch-up but not how long it keeps recordings; Kodi's default too. */
export const UNKNOWN_CATCH_UP_DAYS = 5;

// ---- The grid ----

/** The half hour [time] falls in, where the timeline's ticks are. */
export const slotStart = (time: number): number => Math.floor(time / SLOT) * SLOT;

/** Half-hour ticks from the one at or before [from] up to [to]. */
export function ticks(from: number, to: number): number[] {
    const out: number[] = [];
    for (let t = slotStart(from); t <= to; t += SLOT) out.push(t);
    return out;
}

/** How far back the grid scrolls: an hour, or as many days as the longest catch-up a channel offers. */
export const earliest = (now: number, catchUpDays: number): number =>
    slotStart(now - Math.max(HOUR, clamp(catchUpDays, 0, MAX_CATCH_UP_DAYS) * DAY));

/** How far ahead it scrolls: as many days as the guide keeps. */
export const latest = (now: number, guideDays: number): number => slotStart(now) + Math.max(1, guideDays) * DAY;

/** Where a scroll may start, so the visible span never runs past either end. */
export const clampStart = (start: number, first: number, last: number, visible: number): number =>
    clamp(start, first, Math.max(first, last - visible));

/** The programmes of [all] that are on at any point between [from] and [to], in order. */
export function overlapping<T extends { start: number; stop: number }>(all: readonly T[], from: number, to: number): T[] {
    return all.filter((p) => p.stop > from && p.start < to).sort((a, b) => a.start - b.start);
}

/**
 * The start of the view that brings a programme into sight, as on Android: one off the right edge scrolls just far
 * enough to show its start, one wholly past the left edge scrolls to its start, and one still on leaves time alone.
 */
export function reveal(start: number, stop: number, viewStart: number, visible: number): number {
    if (start >= viewStart + visible - SLOT) return slotStart(start) - visible / 2;
    if (stop > viewStart && start < viewStart + visible) return viewStart;
    if (start < viewStart) return slotStart(start);
    return viewStart;
}

/** The programme on at [time] in a channel's sorted list, or the first one after it, or null. */
export function at<T extends { start: number; stop: number }>(list: readonly T[], time: number): T | null {
    for (const p of list) if (p.stop > time) return p;
    return null;
}

// ---- Catch-up ----

/** How a channel's recordings are reached: Xtream's timeshift (`xc`), or a playlist's `catchup` attributes. */
export interface CatchUp {
    readonly mode: "default" | "append" | "shift" | "flussonic" | "xc";
    readonly days: number;
    /** The link template `default` and `append` need. */
    readonly source: string | null;
}

const MODES = new Set(["default", "append", "shift", "flussonic", "xc"]);

/** Days of recordings from a `get_live_streams` entry; servers send the values as numbers or strings. */
export function xtreamDays(tvArchive: unknown, duration: unknown): number {
    if (String(tvArchive ?? "").trim() !== "1") return 0;
    const days = parseInt(String(duration ?? "").trim(), 10);
    return Number.isFinite(days) ? clamp(days, 0, MAX_CATCH_UP_DAYS) : 0;
}

/** A playlist's catch-up as tv/core encodes it: the mode, a line break, then the template; null if none. */
export function decodeCatchUp(text: string | null | undefined, days: number): CatchUp | null {
    if (!text) return null;
    const cut = text.indexOf("\n");
    const mode = cut < 0 ? text : text.slice(0, cut);
    if (!MODES.has(mode)) return null;
    const source = cut < 0 ? "" : text.slice(cut + 1).trim();
    return { mode: mode as CatchUp["mode"], days: clamp(days || UNKNOWN_CATCH_UP_DAYS, 1, MAX_CATCH_UP_DAYS), source: source || null };
}

/** Whether a programme starting at [start] can be replayed now, on a channel keeping [days] of recordings. */
export const replayable = (start: number, now: number, days: number): boolean => days > 0 && start < now && start >= now - days * DAY;

/** The oldest start worth keeping in the guide for a channel keeping [days] of recordings. */
export const keepFrom = (now: number, days: number): number => now - clamp(days, 0, MAX_CATCH_UP_DAYS) * DAY;

/** Whole minutes covering a programme, as the timeshift link wants them. */
export const minutes = (start: number, stop: number): number => Math.max(1, Math.ceil((stop - start) / MINUTE));

/**
 * A recording's link for one programme, the way Android, Kodi's IPTV Simple and TiviMate build it, keeping the live
 * link's `|User-Agent=` headers. [zone] is the server's time zone (Xtream's `server_info.timezone`) for the dates in
 * the link, the TV's own when null; null when the live link can't make one.
 */
export function catchUpLink(
    liveLink: string,
    catchUp: CatchUp,
    start: number,
    stop: number,
    now: number,
    zone: string | null,
): string | null {
    const bar = liveLink.indexOf("|");
    const live = bar < 0 ? liveLink : liveLink.slice(0, bar);
    const headers = bar < 0 ? "" : liveLink.slice(bar);
    const t = times(start, stop, now, zone);
    let address: string | null = null;
    switch (catchUp.mode) {
        case "default":
            address = catchUp.source ? fill(catchUp.source, t) : null;
            break;
        case "append":
            address = catchUp.source ? live + fill(catchUp.source, t) : null;
            break;
        case "shift":
            address = `${live}${live.includes("?") ? "&" : "?"}utc=${t.start}&lutc=${t.now}`;
            break;
        case "flussonic":
            address = catchUp.source ? fill(catchUp.source, t) : flussonic(live, t);
            break;
        case "xc":
            address = xtream(live, start, stop, t);
            break;
    }
    return address === null ? null : address + headers;
}

interface Parts {
    readonly year: number;
    readonly month: number;
    readonly day: number;
    readonly hour: number;
    readonly minute: number;
    readonly second: number;
}

interface Times {
    readonly start: number;
    readonly end: number;
    readonly now: number;
    readonly duration: number;
    readonly startDate: Parts;
    readonly endDate: Parts;
    readonly nowDate: Parts;
}

function times(startMs: number, stopMs: number, nowMs: number, zone: string | null): Times {
    const start = Math.floor(startMs / 1000);
    const end = Math.floor(stopMs / 1000);
    const now = Math.floor(nowMs / 1000);
    return {
        start,
        end,
        now,
        duration: Math.max(1, end - start),
        startDate: partsIn(startMs, zone),
        endDate: partsIn(stopMs, zone),
        nowDate: partsIn(nowMs, zone),
    };
}

const formatters = new Map<string, Intl.DateTimeFormat | null>();

/**
 * The wall-clock date and time of [ms] in [zone] (an IANA name such as `Europe/London`), or in the TV's own zone when
 * the server doesn't say or names one the TV doesn't know, as Android falls back to the device's.
 */
export function partsIn(ms: number, zone: string | null): Parts {
    const local = (): Parts => {
        const d = new Date(ms);
        return {
            year: d.getFullYear(),
            month: d.getMonth() + 1,
            day: d.getDate(),
            hour: d.getHours(),
            minute: d.getMinutes(),
            second: d.getSeconds(),
        };
    };
    if (!zone) return local();
    let format = formatters.get(zone);
    if (format === undefined) {
        try {
            format = new Intl.DateTimeFormat("en-GB", {
                timeZone: zone,
                hour12: false,
                year: "numeric",
                month: "2-digit",
                day: "2-digit",
                hour: "2-digit",
                minute: "2-digit",
                second: "2-digit",
            });
        } catch {
            format = null;
        }
        formatters.set(zone, format);
    }
    if (!format) return local();
    try {
        const got: Record<string, number> = {};
        for (const p of format.formatToParts(new Date(ms))) if (p.type !== "literal") got[p.type] = Number(p.value);
        return {
            year: got.year,
            month: got.month,
            day: got.day,
            hour: got.hour % 24,
            minute: got.minute,
            second: got.second,
        };
    } catch {
        return local();
    }
}

const pad = (n: number, width = 2) => String(n).padStart(width, "0");

const XTREAM_LIVE = /^(https?:\/\/[^/]+)(?:\/live)?\/([^/]+)\/([^/]+)\/(\d+)(\.[A-Za-z0-9]+)?$/;

/** Xtream's timeshift link, from a live link of the `/live/user/pass/id.ts` shape. */
function xtream(live: string, startMs: number, stopMs: number, t: Times): string | null {
    const m = XTREAM_LIVE.exec(live.split("?")[0]);
    if (!m) return null;
    const d = t.startDate;
    const when = `${pad(d.year, 4)}-${pad(d.month)}-${pad(d.day)}:${pad(d.hour)}-${pad(d.minute)}`;
    return `${m[1]}/timeshift/${m[2]}/${m[3]}/${minutes(startMs, stopMs)}/${when}/${m[4]}${m[5] || ".ts"}`;
}

/** Flussonic: `…/index.m3u8` becomes `…/index-<start>-<duration>.m3u8`, `…/mpegts` becomes `…/timeshift_abs-<start>.ts`. */
function flussonic(live: string, t: Times): string | null {
    const q = live.indexOf("?");
    const path = q < 0 ? live : live.slice(0, q);
    const query = q < 0 ? "" : live.slice(q);
    if (path.endsWith("/mpegts")) return `${path.slice(0, -"/mpegts".length)}/timeshift_abs-${t.start}.ts${query}`;
    const hls = /\/([^/?]+)\.m3u8$/;
    if (hls.test(path)) return path.replace(hls, (_, name: string) => `/${name}-${t.start}-${t.duration}.m3u8`) + query;
    return null;
}

const PLACEHOLDER = /\$?\{([^{}]+)\}/g;

/** Fills a template's `{utc}`, `${start}`, `{utc:Y-m-d}`, `{duration:60}`, `${(b)yyyyMMddHHmmss}` and the rest. */
function fill(template: string, t: Times): string {
    return template.replace(PLACEHOLDER, (whole, inner: string) => value(inner.trim(), t) ?? whole);
}

function value(name: string, t: Times): string | null {
    const colon = name.indexOf(":");
    const key = colon < 0 ? name : name.slice(0, colon);
    const arg = colon < 0 ? "" : name.slice(colon + 1);
    const divisor = () => {
        const n = parseInt(arg, 10);
        return n > 0 ? n : 1;
    };
    switch (key) {
        case "utc":
        case "start":
            return arg ? strftime(arg, t.startDate) : String(t.start);
        case "utcend":
        case "end":
            return arg ? strftime(arg, t.endDate) : String(t.end);
        case "lutc":
        case "now":
        case "timestamp":
            return arg ? strftime(arg, t.nowDate) : String(t.now);
        case "duration":
            return String(Math.floor(t.duration / divisor()));
        case "offset":
            return String(Math.floor(Math.max(0, t.now - t.start) / divisor()));
        case "Y":
        case "m":
        case "d":
        case "H":
        case "M":
        case "S":
            return strftime(key, t.startDate);
    }
    if (name.startsWith("(b)")) return pattern(name.slice(3), t.startDate);
    if (name.startsWith("(e)")) return pattern(name.slice(3), t.endDate);
    return null;
}

/** `Y-m-d:H-M` style formats: each letter is a strftime field, anything else is copied. */
function strftime(format: string, d: Parts): string {
    let out = "";
    for (const c of format) {
        if (c === "Y") out += pad(d.year, 4);
        else if (c === "m") out += pad(d.month);
        else if (c === "d") out += pad(d.day);
        else if (c === "H") out += pad(d.hour);
        else if (c === "M") out += pad(d.minute);
        else if (c === "S") out += pad(d.second);
        else out += c;
    }
    return out;
}

/** `yyyyMMddHHmmss` style formats, read as Java date patterns (the letters Kodi's templates use); null if unreadable. */
function pattern(format: string, d: Parts): string | null {
    let out = "";
    let i = 0;
    while (i < format.length) {
        const c = format[i];
        if (c === "'") {
            const close = format.indexOf("'", i + 1);
            if (close < 0) return null;
            out += close === i + 1 ? "'" : format.slice(i + 1, close);
            i = close + 1;
            continue;
        }
        let run = 1;
        while (format[i + run] === c) run++;
        if (/[A-Za-z]/.test(c)) {
            const field: Record<string, number> = { y: d.year, M: d.month, d: d.day, H: d.hour, m: d.minute, s: d.second };
            if (!(c in field)) return null;
            out += c === "y" && run === 2 ? pad(d.year % 100) : pad(field[c], run);
        } else out += c.repeat(run);
        i += run;
    }
    return out;
}

function clamp(n: number, low: number, high: number): number {
    return Math.min(high, Math.max(low, n));
}
