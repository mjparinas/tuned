// Live events for the TV apps (docs/decisions/events.md): today's games, fights and pay-per-views from the guide, and
// from a pay-per-view slot's own name when its guide is empty ("PPV 7: UFC 312 10PM ET"), one row per event with the
// channels showing it; the names a person follows; and auto-tune's order for trying those channels one at a time
// (docs/decisions/auto-tune.md). The rules are core/Events.kt, core/ChannelEvents.kt, core/EventChannels.kt and
// core/Tuner.kt, ported with their tests' cases. Plain TypeScript with no tv/core and no regex lookbehind or \p{L},
// which Vega's Hermes doesn't have, so both TV apps take it. `@tuned/shared/events`.
import type { KeyValueStore } from "./platform.ts";
import { searchText } from "./search.ts";

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** The same title on several channels starting within this long of each other is one event. */
export const SAME_START_MS = 30 * MINUTE;
/** How long an event read from a name lasts, since the name gives only its start. */
export const NAMED_LENGTH_MS = 3 * HOUR;
/** How far ahead events are listed, as on Android. */
export const EVENTS_AHEAD_MS = 36 * HOUR;

/** Letters, spelled out rather than \p{L}. */
const L = "A-Za-z\\u00C0-\\u024F";

// ---- Which channels and titles are events (core/EventChannels.kt, core/Events.kt) ----

const sporty = new RegExp(
    "\\b(sports?|ppv|events?|ufc|wwe|aew|dazn|espn\\w*|tsn\\d?|sportsnet|bein|fox sports|sky sports|bt sport|tnt sports|" +
        "nfl|nba|nhl|mlb|mls|ncaa|epl|premier league|laliga|serie a|bundesliga|ligue 1|f1|formula|motogp|nascar|indycar|" +
        "golf|tennis|cricket|rugby|boxing|fight|fite|flo\\w*|fubo|willow|super ?sport|match ?day|game ?day|" +
        "football|soccer|hockey|baseball|basketball|wrestling|mma)\\b",
    "i",
);

/** Whether a channel's name or category marks it as sport, pay-per-view or an event slot. */
export const sportyChannel = (name: string, group: string): boolean => sporty.test(`${group} ${name}`);

const matchTitle = /\S.*\s(vs?\.?|@|at)\s.*\S/i;
const eventWords = new RegExp(
    "\\b(ufc \\d+|ufc fight night|fight night|ppv|pay[- ]per[- ]view|grand prix|gp|super bowl|stanley cup|world series|" +
        "world cup|final|finals|semi-?final|playoffs?|championship|open|derby|classic|bowl|wrestlemania|" +
        "main card|prelims|undercard|title fight|boxing)\\b",
    "i",
);
const filler = new RegExp(
    "\\b(highlights|replay|re-air|encore|rewind|best of|classics|preview|review|magazine|news|centre|center|tonight|" +
        "daily|show|podcast|talk|countdown|paid programming|off air|no event|to be announced|tba|coming soon|" +
        "infomercial|documentary|30 for 30|top \\d+)\\b",
    "i",
);
const markers = /^\s*(live|en vivo|direct)\s*[:\-|]\s*|\s*[(\[](live|l|r|hd|fhd|4k|new)[)\]]\s*/gi;

/** The title without "LIVE:", "(L)" and similar, as shown. */
export const cleanTitle = (title: string): string =>
    title
        .replace(markers, " ")
        .trim()
        .replace(/\s{2,}/g, " ");

/** Whether a title on a sports channel is an event worth listing, rather than a show or a replay. */
export function isEvent(title: string): boolean {
    const clean = cleanTitle(title);
    if (clean.length < 3) return false;
    if (filler.test(clean)) return false;
    return matchTitle.test(clean) || eventWords.test(clean);
}

/** What the same event's listings on different channels have in common. */
export const eventKey = (title: string): string => searchText(cleanTitle(title));

// ---- One row per event ----

/** One programme in the guide, on a sports channel. */
export interface EventProgramme {
    channelId: string;
    title: string;
    start: number;
    stop: number;
}

export interface LiveEvent {
    key: string;
    title: string;
    start: number;
    stop: number;
    /** The channels showing it, in the order the guide listed them. */
    channels: string[];
    /** False for an event read from a name that gives no time: listed under today, and Tune stays on. */
    timeKnown: boolean;
}

export const liveAt = (e: LiveEvent, now: number): boolean => e.timeKnown && e.start <= now && now < e.stop;

const byLiveThenStart = (now: number) => (a: LiveEvent, b: LiveEvent) =>
    Number(liveAt(b, now)) - Number(liveAt(a, now)) || a.start - b.start;

/**
 * The guide's events: the same title on several channels, starting within half an hour, is one event; a replay hours
 * later is another. Ended ones are left out; the rest come live first, then by start.
 */
export function groupEvents(rows: readonly EventProgramme[], now: number): LiveEvent[] {
    const events: LiveEvent[] = [];
    const open = new Map<string, number[]>();
    for (const row of [...rows].sort((a, b) => a.start - b.start)) {
        if (row.stop <= now || !isEvent(row.title)) continue;
        const key = eventKey(row.title);
        let candidates = open.get(key);
        if (!candidates) open.set(key, (candidates = []));
        const at = candidates.find((i) => Math.abs(events[i].start - row.start) <= SAME_START_MS);
        if (at === undefined) {
            candidates.push(events.length);
            events.push({
                key,
                title: cleanTitle(row.title),
                start: row.start,
                stop: row.stop,
                channels: [row.channelId],
                timeKnown: true,
            });
        } else {
            const e = events[at];
            if (!e.channels.includes(row.channelId)) {
                events[at] = { ...e, stop: Math.max(e.stop, row.stop), channels: [...e.channels, row.channelId] };
            }
        }
    }
    return events.sort(byLiveThenStart(now));
}

/**
 * Adds events read from channel names to the guide's: one the guide has (the same title starting within half an hour,
 * or at any time when the name gives none) gains the channel; the rest stand alone, lasting three hours.
 */
export function withNamed(
    events: readonly LiveEvent[],
    named: readonly { channelId: string; event: NamedEvent }[],
    now: number,
): LiveEvent[] {
    const all = [...events];
    for (const { channelId, event: n } of named) {
        const key = eventKey(n.title);
        const at = all.findIndex(
            (e) => e.key === key && (n.start === null || !e.timeKnown || Math.abs(e.start - n.start) <= SAME_START_MS),
        );
        if (at >= 0) {
            const e = all[at];
            if (!e.channels.includes(channelId)) all[at] = { ...e, channels: [...e.channels, channelId] };
        } else {
            const start = n.start ?? now;
            all.push({ key, title: n.title, start, stop: start + NAMED_LENGTH_MS, channels: [channelId], timeKnown: n.start !== null });
        }
    }
    return all.sort(byLiveThenStart(now));
}

// ---- Following teams, leagues and fighters ----

/** Whether the event is about something followed ("UFC", "Canucks"): a whole word, so "Can" isn't "Canucks". */
export function follows(event: Pick<LiveEvent, "key">, followed: readonly string[]): boolean {
    const words = ` ${event.key} `;
    return followed.some((f) => {
        const k = searchText(f);
        return k.length > 0 && words.includes(` ${k} `);
    });
}

/** The events whose alerts go when `name` is unfollowed: those it matched that nothing still followed covers. */
export const noLongerFollowed = (events: readonly LiveEvent[], name: string, followed: readonly string[]): LiveEvent[] =>
    events.filter((e) => follows(e, [name]) && !follows(e, followed));

/**
 * The names followed on the phone or the website (Settings' `followed_events`), from the account's backup
 * (`GET /api/backup`, Android's sync copy, kept as `{fingerprint, changedAt, copy}`). Empty when it has none.
 */
export function followsFromBackup(backup: string | null): string[] {
    if (!backup) return [];
    try {
        const whole = JSON.parse(backup) as Record<string, unknown> | null;
        const copy = (whole && typeof whole.copy === "object" && whole.copy ? whole.copy : whole) as Record<string, unknown> | null;
        const settings = copy?.settings as Record<string, { set?: unknown }> | undefined;
        const set = settings?.followed_events?.set;
        return Array.isArray(set) ? set.filter((s): s is string => typeof s === "string" && s.trim().length > 0) : [];
    } catch {
        return [];
    }
}

// ---- Events in channel names (core/ChannelEvents.kt) ----

/** What a channel's name says: the event and, when it gives one, when it starts. */
export interface NamedEvent {
    title: string;
    start: number | null;
}

class NoSuchDate extends Error {}

const WEEKDAY = "(?:mon|monday|tue|tues|tuesday|wed|wednesday|thu|thur|thurs|thursday|fri|friday|sat|saturday|sun|sunday)\\.?";
const MONTH =
    "(?:jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|" +
    "nov|november|dec|december)\\.?";
const DATE = `(?:\\d{4}-\\d{1,2}-\\d{1,2}|\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?|\\d{1,2}(?:st|nd|rd|th)?\\s+${MONTH}|${MONTH}\\s+\\d{1,2}(?:st|nd|rd|th)?)`;
const ZONE = "(?:et|est|edt|ct|cst|cdt|mt|mst|mdt|pt|pst|pdt|at|ast|adt|gmt|utc|bst|uk|cet|cest|aest|aedt)";

// A time: "10PM", "10:30 pm", "7p", "19:30", "19h30". Not a score or a number inside a word. Group 1 is what came
// before it (the stand-in for a lookbehind).
const timeRe = new RegExp(
    `(^|[^\\d:./])(?:(\\d{1,2})(?:[:.](\\d{2}))?\\s?(a\\.m\\.|p\\.m\\.|am|pm|a|p)(?![${L}\\d])|([01]?\\d|2[0-3])[:h]([0-5]\\d)(?![\\d:]))`,
    "i",
);
// What sits right before a time: its weekday and date, and "@", "at", "on".
const beforeRe = new RegExp(`(^|[^${L}\\d])((?:(?:${WEEKDAY}|${DATE}|@|at|on)[\\s,|\\-]*)+)$`, "i");
// What sits right after it: its zone, then perhaps a date ("9PM ET Sat 9/13").
const afterRe = new RegExp(`^\\s*(?:\\(?${ZONE}\\)?(?![${L}]))?(?:[\\s,]*(?:${WEEKDAY}|${DATE})(?![${L}\\d]))*`, "i");
const zoneWord = new RegExp(`(^|[^${L}])(${ZONE})(?![${L}])`, "i");
const weekdayWord = new RegExp(`(^|[^${L}])(${WEEKDAY})(?![${L}])`, "i");
const isoDate = /(\d{4})-(\d{1,2})-(\d{1,2})/;
const slashDate = /(\d{1,2})\/(\d{1,2})(?:\/(\d{2,4}))?/;
const dayMonth = new RegExp(`(\\d{1,2})(?:st|nd|rd|th)?\\s+(${MONTH})`, "i");
const monthDay = new RegExp(`(${MONTH})\\s+(\\d{1,2})`, "i");
const trailingDate = new RegExp(`[(\\[]?\\s*(?:${WEEKDAY}\\s*)?${DATE}\\s*[)\\]]?\\s*$`, "i");
const quality = /[(\[](?:hd|fhd|uhd|sd|4k|hevc|h265|backup|vip)[)\]]|\b(?:fhd|uhd|hevc|h265)\b/gi;
const separator = /\s*(?:[:|»▎┃•]|\s[-–—]+\s|^[-–—]+\s|\s[-–—]+$)\s*/;
// "PPV 7", "NHL 05", "ESPN+ 12": a slot, not an event. "UFC 312" is an event.
const slotRe = new RegExp(`^(?!(?:ufc|bellator|pfl|one|rizin|wwe|aew|ksw|cage warriors)\\b)[${L}+&.' ]{1,24}?\\s*#?\\d{1,3}$`, "i");
const countryRe = /^(?:[A-Z]{2,3}|USA|LATAM|VIP|4K)$/;
const nameMatch = /\S\s(vs?\.?|@)\s\S/i;
const edges = /^[\s|:,@\-–—»▎┃•/]+|[\s|:,@\-–—»▎┃•/(\[]+$/g;
const emptyBrackets = /[(\[][\s|,/\-]*[)\]]/g;

interface Found {
    /** Where the time itself starts and ends (exclusive). */
    start: number;
    end: number;
    groups: RegExpExecArray;
}

function findTime(text: string): Found | null {
    const m = timeRe.exec(text);
    if (!m) return null;
    const start = m.index + m[1].length;
    return { start, end: m.index + m[0].length, groups: m };
}

/** The span of a time with its weekday, date and zone around it: [from, to). */
function chunk(text: string, t: Found): [number, number] {
    const lead = beforeRe.exec(text.slice(0, t.start));
    const from = lead ? lead.index + lead[1].length : t.start;
    const tail = afterRe.exec(text.slice(t.end));
    return [from, t.end + (tail ? tail[0].length : 0)];
}

const isSlot = (text: string) => slotRe.test(text) && !nameMatch.test(text);

/** Reads a channel's name. A time with no zone is in `zone` (null: the TV's own); one with no date is the next not over. */
export function parseChannelName(name: string, now: number, zone: string | null = null): NamedEvent | null {
    let text = ` ${name.replace(quality, " ")} `;
    let start: number | null = null;
    const first = findTime(text);
    if (first) {
        start = startOf(text, first, now, zone);
        // Every time comes off with its date, weekday and zone ("7PM ET / 4PM PT").
        for (let t = findTime(text); t; t = findTime(text)) {
            const [from, to] = chunk(text, t);
            text = `${text.slice(0, from)} | ${text.slice(to)}`;
        }
    }
    text = text.replace(trailingDate, " ");
    const [title, split] = strip(text);
    if (title.length < 4 || isSlot(title) || !isEvent(title)) return null;
    // A plain channel name ("Sky Sports Golf Open") only counts with a time, a slot label or a match.
    if (start === null && !split && !nameMatch.test(title)) return null;
    return { title: cleanTitle(title), start };
}

/** The events named by sports channels, from `now` to `to`. One bad name never stops the others. */
export function eventsInNames(
    channels: readonly { id: string; name: string }[],
    now: number,
    to: number,
    zone: string | null = null,
): { channelId: string; event: NamedEvent }[] {
    const out: { channelId: string; event: NamedEvent }[] = [];
    for (const c of channels) {
        let event: NamedEvent | null;
        try {
            event = parseChannelName(c.name, now, zone);
        } catch (error) {
            if (!(error instanceof NoSuchDate)) throw error;
            event = null; // "2/30": skip this name, keep the rest.
        }
        if (!event) continue;
        if (event.start !== null && (event.start + NAMED_LENGTH_MS <= now || event.start >= to)) continue;
        out.push({ channelId: c.id, event });
    }
    return out;
}

function strip(input: string): [string, boolean] {
    let rest = input.trim().replace(edges, "");
    let split = false;
    for (;;) {
        const sep = separator.exec(rest);
        if (!sep) break;
        if (sep.index === 0) {
            if (!sep[0].length) break;
            rest = rest.slice(sep[0].length);
            continue;
        }
        const left = rest.slice(0, sep.index).trim();
        const right = rest.slice(sep.index + sep[0].length).trim();
        const slot = isSlot(left);
        const label = slot || countryRe.test(left) || (sportyChannel(left, "") && !isEvent(left));
        if (!label || right.length < 4) break;
        rest = right;
        // A country or "Sky Sports" off the front says nothing; "PPV 7:" says an event follows.
        split = split || slot;
    }
    return [
        rest
            .replace(emptyBrackets, " ")
            .replace(edges, "")
            .replace(/\s{2,}/g, " "),
        split,
    ];
}

interface Day {
    y: number;
    m: number;
    d: number;
}

const plusDays = (day: Day, n: number): Day => {
    const t = new Date(Date.UTC(day.y, day.m - 1, day.d + n));
    return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() };
};
const weekdayOf = (day: Day) => new Date(Date.UTC(day.y, day.m - 1, day.d)).getUTCDay();
const daysIn = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate();
const dayNumber = (day: Day) => Date.UTC(day.y, day.m - 1, day.d) / DAY;

function validDay(y: number, m: number, d: number): Day {
    if (m < 1 || m > 12 || d < 1 || d > daysIn(y, m)) throw new NoSuchDate(`${y}-${m}-${d}`);
    return { y, m, d };
}

function startOf(text: string, t: Found, now: number, localZone: string | null): number | null {
    const g = t.groups;
    let hour: number;
    let minute: number;
    if (g[4]) {
        const h = Number(g[2]);
        if (h < 1 || h > 12) return null;
        hour = (h % 12) + (g[4].toLowerCase().startsWith("p") ? 12 : 0);
        minute = Number(g[3] || 0);
    } else {
        hour = Number(g[5]);
        minute = Number(g[6]);
    }
    if (minute > 59) return null;
    const [from, to] = chunk(text, t);
    const around = text.slice(from, to);
    const zoned = zoneWord.exec(text.slice(t.end, to));
    const zone = zoned ? zoneOfWord(zoned[2]) : localZone;
    const today = wallDay(now, zone);
    const date = dateIn(around, today);
    if (date) return wallToEpoch(date, hour, minute, zone);
    const wd = weekdayWord.exec(around);
    const weekday = wd ? weekdayIndex(wd[2]) : null;
    // The first such time that hasn't ended: a name is renamed for what's on or coming up.
    const last = weekday !== null ? 7 : 1;
    for (let n = -1; n <= last; n++) {
        const day = plusDays(today, n);
        if (weekday !== null && weekdayOf(day) !== weekday) continue;
        const at = wallToEpoch(day, hour, minute, zone);
        if (at + NAMED_LENGTH_MS > now) return at;
    }
    return null;
}

/** A date written in `text`, in the year that puts it nearest `today`. */
function dateIn(text: string, today: Day): Day | null {
    const iso = isoDate.exec(text);
    if (iso) return validDay(Number(iso[1]), Number(iso[2]), Number(iso[3]));
    let month: number;
    let day: number;
    let year: number | null = null;
    const slash = slashDate.exec(text);
    const dm = slash ? null : dayMonth.exec(text);
    const md = slash || dm ? null : monthDay.exec(text);
    if (slash) {
        const a = Number(slash[1]);
        const b = Number(slash[2]);
        if (slash[3]) year = Number(slash[3]) < 100 ? 2000 + Number(slash[3]) : Number(slash[3]);
        // Month first, as North American providers write it, unless that can't be a month.
        [month, day] = a > 12 ? [b, a] : [a, b];
    } else if (dm) {
        month = monthIndex(dm[2]);
        day = Number(dm[1]);
    } else if (md) {
        month = monthIndex(md[1]);
        day = Number(md[2]);
    } else {
        return null;
    }
    if (month < 1 || month > 12 || day < 1 || day > 31) return null;
    if (year !== null) return validDay(year, month, day);
    let best: Day | null = null;
    for (let y = today.y - 1; y <= today.y + 1; y++) {
        if (day > daysIn(y, month)) continue;
        const candidate = { y, m: month, d: day };
        if (!best || Math.abs(dayNumber(candidate) - dayNumber(today)) < Math.abs(dayNumber(best) - dayNumber(today))) best = candidate;
    }
    if (!best) throw new NoSuchDate(`${month}/${day}`);
    return best;
}

function zoneOfWord(word: string): string {
    switch (word.toLowerCase()) {
        case "et":
        case "est":
        case "edt":
            return "America/New_York";
        case "ct":
        case "cst":
        case "cdt":
            return "America/Chicago";
        case "mt":
        case "mst":
        case "mdt":
            return "America/Denver";
        case "pt":
        case "pst":
        case "pdt":
            return "America/Los_Angeles";
        case "at":
        case "ast":
        case "adt":
            return "America/Halifax";
        case "bst":
        case "uk":
            return "Europe/London";
        case "cet":
        case "cest":
            return "Europe/Paris";
        case "aest":
        case "aedt":
            return "Australia/Sydney";
        default:
            return "UTC";
    }
}

const WEEKDAYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];
const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];
const weekdayIndex = (word: string) => WEEKDAYS.indexOf(word.toLowerCase().slice(0, 3));
const monthIndex = (word: string) => MONTHS.indexOf(word.toLowerCase().slice(0, 3)) + 1;

// ---- Time zones without relying on Intl ----

/** Daylight time rules for the zones names give, for an engine whose Intl can't do time zones. */
const RULES: Record<string, { base: number; dst: "us" | "eu" | "au" | null }> = {
    "America/New_York": { base: -300, dst: "us" },
    "America/Toronto": { base: -300, dst: "us" },
    "America/Chicago": { base: -360, dst: "us" },
    "America/Winnipeg": { base: -360, dst: "us" },
    "America/Denver": { base: -420, dst: "us" },
    "America/Edmonton": { base: -420, dst: "us" },
    "America/Los_Angeles": { base: -480, dst: "us" },
    "America/Halifax": { base: -240, dst: "us" },
    "Europe/London": { base: 0, dst: "eu" },
    "Europe/Paris": { base: 60, dst: "eu" },
    "Australia/Sydney": { base: 600, dst: "au" },
    UTC: { base: 0, dst: null },
};

/** The nth (1-based; -1 for the last) Sunday of a month, as a UTC day. */
function sunday(y: number, m: number, nth: number): Day {
    if (nth > 0) {
        const first = weekdayOf({ y, m, d: 1 });
        return { y, m, d: 1 + ((7 - first) % 7) + (nth - 1) * 7 };
    }
    const lastDay = daysIn(y, m);
    return { y, m, d: lastDay - weekdayOf({ y, m, d: lastDay }) };
}

/** The offset from UTC in minutes by the rules above. */
export function ruleOffset(zone: string, utc: number): number | null {
    const rule = RULES[zone];
    if (!rule) return null;
    const y = new Date(utc).getUTCFullYear();
    const at = (day: Day, hourUtc: number) => Date.UTC(day.y, day.m - 1, day.d, hourUtc);
    let summer = false;
    if (rule.dst === "us") {
        // 2 a.m. local, second Sunday of March to first Sunday of November.
        summer = utc >= at(sunday(y, 3, 2), 2 - rule.base / 60) && utc < at(sunday(y, 11, 1), 2 - (rule.base + 60) / 60);
    } else if (rule.dst === "eu") {
        // 1 a.m. UTC, last Sunday of March to last Sunday of October.
        summer = utc >= at(sunday(y, 3, -1), 1) && utc < at(sunday(y, 10, -1), 1);
    } else if (rule.dst === "au") {
        // 2 a.m. standard time, first Sunday of October to first Sunday of April.
        summer = utc < at(sunday(y, 4, 1), 2 - rule.base / 60) || utc >= at(sunday(y, 10, 1), 2 - rule.base / 60);
    }
    return rule.base + (summer ? 60 : 0);
}

const formatters = new Map<string, Intl.DateTimeFormat | null>();

/** The offset from UTC in minutes of `zone` at `utc`: the engine's own when it knows zones, else the rules. */
export function zoneOffset(zone: string | null, utc: number): number {
    if (!zone) return -new Date(utc).getTimezoneOffset();
    let format = formatters.get(zone);
    if (format === undefined) {
        try {
            format =
                typeof Intl !== "undefined" && Intl.DateTimeFormat
                    ? new Intl.DateTimeFormat("en-GB", {
                          timeZone: zone,
                          hour12: false,
                          year: "numeric",
                          month: "2-digit",
                          day: "2-digit",
                          hour: "2-digit",
                          minute: "2-digit",
                      })
                    : null;
            if (format && typeof format.formatToParts !== "function") format = null;
        } catch {
            format = null;
        }
        formatters.set(zone, format);
    }
    if (format) {
        try {
            const p: Record<string, number> = {};
            for (const part of format.formatToParts(new Date(utc))) if (part.type !== "literal") p[part.type] = Number(part.value);
            const wall = Date.UTC(p.year, p.month - 1, p.day, p.hour % 24, p.minute);
            if (Number.isFinite(wall)) return Math.round((wall - Math.floor(utc / MINUTE) * MINUTE) / MINUTE);
        } catch {
            // Falls through to the rules.
        }
    }
    return ruleOffset(zone, utc) ?? -new Date(utc).getTimezoneOffset();
}

function wallDay(utc: number, zone: string | null): Day {
    const t = new Date(utc + zoneOffset(zone, utc) * MINUTE);
    return { y: t.getUTCFullYear(), m: t.getUTCMonth() + 1, d: t.getUTCDate() };
}

function wallToEpoch(day: Day, hour: number, minute: number, zone: string | null): number {
    const guess = Date.UTC(day.y, day.m - 1, day.d, hour, minute);
    const first = guess - zoneOffset(zone, guess) * MINUTE;
    const second = guess - zoneOffset(zone, first) * MINUTE;
    return second;
}

// ---- Auto-tune's order (core/Tuner.kt) ----

/** A channel that might be showing the event, with what the app remembers about it. */
export interface TuneCandidate {
    id: string;
    name: string;
    group: string;
    /** Its country tag ("US"), or null. */
    region: string | null;
    /** The event's words are in its name. */
    nameMatch: boolean;
    /** The guide (or its name) lists the event on it. */
    guideMatch: boolean;
    okAt?: number;
    deadAt?: number;
}

/** A channel that failed within this long is tried last; streams come back, so not for good. */
export const DEAD_FOR_MS = 6 * HOUR;
/** A channel that played within this long counts as known-good. */
export const OK_FOR_MS = 7 * DAY;

const ENGLISH = new Set(["US", "UK", "CA", "EN", "AU", "IE", "NZ"]);
const FIRST = new Set(["US", "CA"]);
const otherLanguage =
    /(^|[^A-Za-zÀ-ɏ])(FRENCH|FRANÇAIS|FRANCAIS|ESPAÑOL|ESPANOL|SPANISH|LATINO|ARABIC|DEUTSCH|GERMAN|ITALIANO|PORTUGUES|PORTUGUÊS|POLSKI|TÜRK|TURK|HINDI|URDU|DUBBED|DUB)(?![A-Za-zÀ-ɏ])/i;

/** 4 for 4K, 3 for full HD, 2 for HD, 1 unknown, 0 for SD. */
export function qualityFromName(text: string): number {
    if (/\b(4K|UHD|2160P?)\b/i.test(text)) return 4;
    if (/\b(FHD|1080[PI]?)\b/i.test(text)) return 3;
    if (/\b(HD|720P?)\b/i.test(text)) return 2;
    if (/\b(SD|LQ|LOW|480P?|576P?|360P?)\b/i.test(text)) return 0;
    return 1;
}

/** 2 for US and Canada, 1 for other English, 0 for unknown, -1 for another language. */
export function english(c: Pick<TuneCandidate, "name" | "group" | "region">): number {
    if (otherLanguage.test(`${c.name} ${c.group}`)) return -1;
    if (!c.region) return 0;
    if (FIRST.has(c.region)) return 2;
    return ENGLISH.has(c.region) ? 1 : -1;
}

/** A channel's country tag from the front of its name or category ("US| ESPN", "CA: Sportsnet"). */
export function regionTag(name: string, group: string): string | null {
    for (const text of [name, group]) {
        const m = /^\s*[[(]?([A-Z]{2,3})[\])]?\s*[|:\-»]/.exec(text);
        if (m && m[1] !== "PPV" && m[1] !== "VIP" && m[1] !== "HD") return m[1] === "GB" ? "UK" : m[1];
    }
    return null;
}

/** The words a name or title must all have for an event search: "UFC 335" and "ufc335" are `ufc`, `335`. */
export function tuneWords(query: string): string[] {
    const words = new Set<string>();
    for (const word of searchText(query).split(" ")) for (const run of word.match(/[0-9]+|[^0-9]+/g) ?? []) words.add(run);
    return [...words].sort((a, b) => b.length - a.length).slice(0, 3);
}

/** Whether `text` has every one of `words`. */
export function hasWords(text: string, words: readonly string[]): boolean {
    if (!words.length) return false;
    const runs = new Set<string>();
    for (const word of searchText(text).split(" ")) for (const run of word.match(/[0-9]+|[^0-9]+/g) ?? []) runs.add(run);
    return words.every((w) => runs.has(w));
}

/** Best first; stable, so equal channels keep the playlist's order. */
export function rankForTune<T extends TuneCandidate>(candidates: readonly T[], now: number): T[] {
    const dead = (c: T) => (c.deadAt ?? 0) > (c.okAt ?? 0) && now - (c.deadAt ?? 0) < DEAD_FOR_MS;
    const good = (c: T) => (c.okAt ?? 0) > 0 && now - (c.okAt ?? 0) < OK_FOR_MS && (c.okAt ?? 0) > (c.deadAt ?? 0);
    return candidates
        .map((c, i) => ({ c, i }))
        .sort(
            (a, b) =>
                Number(dead(a.c)) - Number(dead(b.c)) ||
                english(b.c) - english(a.c) ||
                Number(good(b.c)) - Number(good(a.c)) ||
                Number(b.c.guideMatch) - Number(a.c.guideMatch) ||
                qualityFromName(`${b.c.name} ${b.c.group}`) - qualityFromName(`${a.c.name} ${a.c.group}`) ||
                Number(b.c.nameMatch) - Number(a.c.nameMatch) ||
                a.i - b.i,
        )
        .map((x) => x.c);
}

// ---- What auto-tune remembers per channel ----

export const healthKey = (playlistId: string) => `health.${playlistId}`;
const KEEP_HEALTH = 500;

/** When each channel last played or failed, so the next tune starts on one that works. Per playlist. */
export class ChannelHealth {
    private map: Record<string, { ok: number; dead: number }> = {};
    private readonly store: KeyValueStore;
    private readonly key: string;

    constructor(store: KeyValueStore, key: string) {
        this.store = store;
        this.key = key;
    }

    async load(): Promise<void> {
        try {
            const raw = await this.store.get(this.key);
            const parsed: unknown = raw ? JSON.parse(raw) : {};
            this.map = parsed && typeof parsed === "object" && !Array.isArray(parsed) ? (parsed as typeof this.map) : {};
        } catch {
            this.map = {};
        }
    }

    of(id: string): { okAt: number; deadAt: number } {
        const h = this.map[id];
        return { okAt: h?.ok ?? 0, deadAt: h?.dead ?? 0 };
    }

    played(id: string, now: number = Date.now()): Promise<void> {
        this.map[id] = { ok: now, dead: this.map[id]?.dead ?? 0 };
        return this.save();
    }

    failed(id: string, now: number = Date.now()): Promise<void> {
        this.map[id] = { ok: this.map[id]?.ok ?? 0, dead: now };
        return this.save();
    }

    private async save(): Promise<void> {
        const entries = Object.entries(this.map);
        if (entries.length > KEEP_HEALTH) {
            entries.sort((a, b) => Math.max(b[1].ok, b[1].dead) - Math.max(a[1].ok, a[1].dead));
            this.map = Object.fromEntries(entries.slice(0, KEEP_HEALTH));
        }
        try {
            await this.store.set(this.key, JSON.stringify(this.map));
        } catch {
            // Only a hint for the next tune; losing it costs a slower start.
        }
    }
}
