// The channels and guide (docs/decisions/web.md › The channel list; Melvin, 2026-11-20: "show me all the channels and
// have a link that allows me to open up vlc or some other player? include an epg"; 2026-11-21: "basically the epg
// clone of the app itself", "with logos and everything", "when i click on a channel, that should open up in vlc
// automatically"). Always on for signed-in accounts (watch-first admin: rename, lists and favourites on the website
// without the web_player flag; Melvin, 2026-11-22). The channels and guide come from the copy the Tuned app keeps in
// the account (api/account/channels); only a playlist without one is read straight from the provider, as the provider
// check does. A channel opens in VLC or another player on this device, which connects to the provider itself: this
// page never plays or fetches a stream. Links are never shown on the page (they carry the login); they're only
// handed to the player or copied.

import { drawNav } from "/account/nav.js";
import { enhanceSelect } from "/select.js";
import { access, httpsVariant, isMixed, parseServer } from "/check/verdict.mjs";
import {
    ago,
    applyFilters,
    clickMessage,
    clickPlan,
    fileName,
    fromAppCopy,
    noCopyText,
    GENRES,
    genreOf,
    groupsOf,
    GuideReader,
    guideSpan,
    headerGuide,
    logoSrc,
    m3uFor,
    NO_FILTERS,
    nowNext,
    offers,
    parseM3u,
    platformOf,
    progress,
    QUALITIES,
    ticks,
    TOPICS,
    vlcDidNotOpen,
    vlcLink,
    xtreamChannels,
    xtreamGuideUrl,
    xtreamPlaylistUrl,
} from "/account/channels/core.mjs";
import { wireEdits } from "/account/channels/edits.js";

const $ = (id) => document.getElementById(id);
const LIST_TIMEOUT_MS = 30_000;
const GUIDE_TIMEOUT_MS = 120_000;
/** Rows drawn at a time; more as the guide scrolls down. */
const PAGE = 60;
const GUIDE_BEFORE_MS = 3_600_000;
const GUIDE_AFTER_MS = 24 * 3_600_000;
const HOUR = 3_600_000;
const platform = platformOf(navigator.userAgent, navigator.maxTouchPoints);

const state = {
    playlists: [],
    current: null, // the playlist view from /api/account/library
    channels: [],
    guide: new Map(),
    filters: { ...NO_FILTERS },
    items: [], // the channels the filters leave
    wholeUrl: "",
    shown: PAGE,
    view: "grid",
    span: null, // the guide's { from, to }
    loadId: 0,
    abort: null,
    click: "vlc", // what a click does: "vlc" (open VLC where it can, and copy) or "copy" (copy only)
    profiles: [],
    profileId: 1,
    channelEdits: {},
};

let edits;
edits = wireEdits({
    getState: () => state,
    onLibrary: (library) => edits.takeLibrary(library),
    redraw: () => draw(),
});

const kindNames = {
    xtream: "Xtream login",
    link: "M3U link",
    stalker: "MAC portal",
    plex: "Plex",
    jellyfin: "Jellyfin",
    emby: "Emby",
    file: "File",
};

function tell(id, text, tone = "error") {
    const line = $(id);
    line.textContent = text ?? "";
    line.dataset.tone = tone;
}

// ---------- the account ----------

async function getJson(url) {
    try {
        const res = await fetch(url, { cache: "no-store", credentials: "same-origin" });
        const body = await res.json().catch(() => ({}));
        return { http: res.status, ...body };
    } catch {
        return { http: 0, status: "unavailable" };
    }
}

// ---------- reading the provider ----------

async function timed(url, init = {}, ms = LIST_TIMEOUT_MS) {
    const ctrl = new AbortController();
    const stop = () => ctrl.abort();
    state.abort?.signal.addEventListener("abort", stop);
    const timer = setTimeout(stop, ms);
    try {
        return await fetch(url, { ...init, signal: ctrl.signal, cache: "no-store", credentials: "omit", referrerPolicy: "no-referrer" });
    } finally {
        clearTimeout(timer);
        state.abort?.signal.removeEventListener("abort", stop);
    }
}

let insecureOk = null;
/** Whether this browser lets this https page load http (an opaque request to our own http address). */
async function insecureAllowed() {
    if (location.protocol !== "https:") return true;
    if (insecureOk != null) return insecureOk;
    try {
        await timed(`http://${location.host}/check/ping.txt`, { mode: "no-cors" }, 5000);
        insecureOk = true;
    } catch {
        insecureOk = false;
    }
    return insecureOk;
}

/** A request, and when it fails an opaque one, to tell a missing CORS answer from a provider that isn't there. */
async function probe(url, ms) {
    try {
        const res = await timed(url, {}, ms);
        if (!res.ok) return { how: res.status === 401 || res.status === 403 ? "refused" : "unreachable" };
        return { how: "ok", res };
    } catch {
        if (state.abort?.signal.aborted) return { how: "stopped" };
        let opaque = false;
        try {
            await timed(url, { mode: "no-cors" }, 8000);
            opaque = true;
        } catch {
            opaque = false;
        }
        const mixed = isMixed(url, location.protocol);
        return { how: access({ cors: false, opaque, mixed, insecureAllowed: mixed ? await insecureAllowed() : true }), mixed };
    }
}

const reasons = {
    insecure: "Your provider's address is http, and this browser blocks http from an https page.",
    cors: "Your provider answered, but doesn't allow web pages to read it (no CORS). Only a relay could get round that, and Tuned doesn't relay.",
    unreachable: "Your provider didn't answer this browser. It may be down, or it may block this network.",
    refused: "Your provider turned the login down. Check it in the Tuned app or on the Playlists page.",
};

/** The https twin first (a web page can read it without a setting), then the address as saved. */
function candidates(base) {
    const server = parseServer(base);
    if (!server) return [];
    const twin = httpsVariant(server, null);
    return twin ? [twin, server.base] : [server.base];
}

async function readXtream(login) {
    const saved = parseServer(login.server);
    if (!saved) return { error: "unreachable" };
    let last = { how: "unreachable" };
    for (const base of candidates(login.server)) {
        const api = (action) =>
            `${base}/player_api.php?username=${encodeURIComponent(login.username)}&password=${encodeURIComponent(login.password)}&action=${action}`;
        const cats = await probe(api("get_live_categories"));
        if (cats.how === "stopped") return { error: "stopped" };
        if (cats.how !== "ok") {
            last = cats;
            continue;
        }
        tell("status", "Reading the channel list from your provider…", "ok");
        const streams = await probe(api("get_live_streams"), 90_000);
        if (streams.how !== "ok") {
            last = streams;
            continue;
        }
        const categories = await cats.res.json().catch(() => null);
        const list = await streams.res.json().catch(() => null);
        if (!Array.isArray(list)) return { error: "refused" };
        // Links for the player use the address as saved: VLC has no https-page rule, and it's what the app uses.
        return {
            channels: xtreamChannels(categories, list, saved.base, login.username, login.password),
            guides: [login.guide || xtreamGuideUrl(base, login.username, login.password), ...(login.extraGuides ?? [])],
        };
    }
    return { error: last.how, mixed: last.mixed };
}

async function readLink(login) {
    let last = { how: "unreachable" };
    const saved = login.link;
    const tries = [];
    try {
        const url = new URL(saved);
        if (url.protocol === "http:") tries.push(`https://${url.host}${url.pathname}${url.search}`);
    } catch {
        return { error: "unreachable" };
    }
    tries.push(saved);
    for (const url of tries) {
        const got = await probe(url, 90_000);
        if (got.how === "stopped") return { error: "stopped" };
        if (got.how !== "ok") {
            last = got;
            continue;
        }
        const text = await got.res.text().catch(() => "");
        const channels = parseM3u(text);
        if (!channels.length) return { error: "empty" };
        return { channels, guides: [login.guide || headerGuide(text), ...(login.extraGuides ?? [])].filter(Boolean) };
    }
    return { error: last.how, mixed: last.mixed };
}

/** Streams one guide into the reader as it downloads (gzip undone in the browser), and says whether it worked. */
async function readGuide(url, reader, onProgress) {
    const tries = [];
    try {
        const u = new URL(url);
        if (u.protocol === "http:") tries.push(`https://${u.host}${u.pathname}${u.search}`);
    } catch {
        return false;
    }
    tries.push(url);
    for (const attempt of tries) {
        const got = await probe(attempt, GUIDE_TIMEOUT_MS);
        if (got.how !== "ok" || !got.res.body) continue;
        try {
            let body = got.res.body;
            const gz = /\.gz($|\?)/i.test(attempt) || /gzip/i.test(got.res.headers.get("content-type") ?? "");
            if (gz && "DecompressionStream" in window) body = body.pipeThrough(new DecompressionStream("gzip"));
            const text = body.pipeThrough(new TextDecoderStream());
            const r = text.getReader();
            let ticks = 0;
            for (;;) {
                const { value, done } = await r.read();
                if (done) break;
                reader.feed(value);
                if (++ticks % 40 === 0) onProgress(reader.count);
            }
            return true;
        } catch {
            if (state.abort?.signal.aborted) return false;
        }
    }
    return false;
}

// ---------- opening a channel in the player ----------

function download(name, text) {
    const blob = new Blob([text], { type: "audio/x-mpegurl" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
}

/** Copies text, with the old way for browsers that refuse the clipboard API. Starts at once, inside the click. */
async function copyText(text) {
    try {
        await navigator.clipboard.writeText(text);
        return true;
    } catch {
        const box = document.createElement("textarea");
        box.value = text;
        box.setAttribute("readonly", "");
        box.style.position = "fixed";
        box.style.opacity = "0";
        document.body.append(box);
        box.select();
        let ok = false;
        try {
            ok = document.execCommand("copy");
        } catch {
            ok = false;
        }
        box.remove();
        return ok;
    }
}

let toastTimer = 0;
/** A line at the bottom of the screen that every click leaves, wherever the page is scrolled to. */
function toast(text, tone = "ok") {
    const box = $("toast");
    clearTimeout(toastTimer);
    box.textContent = text;
    box.dataset.tone = tone;
    box.hidden = false;
    toastTimer = setTimeout(() => (box.hidden = true), 7000);
}

async function copy(text, where, what) {
    const copied = await copyText(text);
    const message = copied
        ? `${what} copied. Paste it into VLC (Media › Open Network Stream) or any player.`
        : "This browser didn't allow copying. Use the playlist file instead.";
    tell(where, message, copied ? "ok" : "error");
    toast(message, copied ? "ok" : "error");
}

/** Says so when VLC's link left the page in front, which usually means VLC isn't on this device. */
function watchVlc(name) {
    let left = false;
    const away = () => (left = true);
    window.addEventListener("blur", away, { once: true });
    document.addEventListener("visibilitychange", away, { once: true });
    setTimeout(() => {
        window.removeEventListener("blur", away);
        document.removeEventListener("visibilitychange", away);
        if (!left && document.hasFocus()) toast(vlcDidNotOpen(name), "error");
    }, 3000);
}

/**
 * A click on a channel or a programme: the link is copied, and VLC opens too where it has a link of its own
 * (Android, iPhone and iPad, Mac) unless the person chose "Copy the link". When the browser refuses to copy and VLC
 * can't open, the one-channel playlist file downloads instead, so a click always does something and says what.
 */
async function play(c) {
    const { vlc } = clickPlan(c.url, platform, c.name, state.click);
    // Copy before VLC takes the focus: the clipboard only works while the page is in front.
    const copying = copyText(c.url);
    if (vlc) location.href = vlc;
    const copied = await copying;
    if (!vlc && !copied) download(fileName(c.name), m3uFor([c]));
    const { text, tone } = clickMessage({ name: c.name, vlc, copied });
    toast(text, tone);
    if ($("show").open) tell("show-message", text, tone);
    if (vlc && copied) watchVlc(c.name);
}

const playLabel = (c) =>
    clickPlan(c.url, platform, c.name, state.click).vlc ? `Open ${c.name} in VLC` : `Copy ${c.name}'s link for your player`;

/** The choice of what a click does, kept in this browser. Only where VLC has a link: elsewhere a click copies. */
const CLICK_KEY = "tuned.channels.click";
function setUpClick() {
    try {
        state.click = localStorage.getItem(CLICK_KEY) === "copy" ? "copy" : "vlc";
    } catch {
        state.click = "vlc";
    }
    const canOpen = !!vlcLink("http://example.com/1.ts", platform);
    $("click-field").hidden = !canOpen;
    if (!canOpen) return;
    $("click").value = state.click;
    enhanceSelect($("click"));
    $("click").onchange = () => {
        state.click = $("click").value === "copy" ? "copy" : "vlc";
        try {
            localStorage.setItem(CLICK_KEY, state.click);
        } catch {
            // Private windows can refuse storage; the choice still holds until the page closes.
        }
        toast(state.click === "copy" ? "A click now copies the link." : "A click now opens VLC and copies the link.");
        if (state.channels.length) draw();
    };
}

function drawWhole() {
    const url = state.wholeUrl;
    const name = state.current?.name || state.current?.detail || "Tuned playlist";
    const vlc = url ? vlcLink(url, platform, name) : null;
    $("whole-card").hidden = !url;
    $("whole-vlc").hidden = !vlc;
    if (vlc) $("whole-vlc").href = vlc;
    // A playlist whose one entry is the provider's own playlist link: VLC opens it and reads the whole list itself.
    $("whole-file").onclick = () => download(fileName(name), m3uFor([{ name, url }]));
    $("whole-copy").onclick = () => copy(url, "pick-message", "Playlist link");
}

// ---------- drawing ----------

const time = (ms) => new Date(ms).toLocaleTimeString("en-CA", { hour: "numeric", minute: "2-digit" });
const programmes = (c) => (c.epgId ? state.guide.get(c.epgId) : null) ?? null;
const nowOf = (c) => nowNext(programmes(c), Date.now()).now;
const genreName = (code) => GENRES.find((g) => g.code === code)?.label ?? "";
const zone = (() => {
    try {
        return (
            new Intl.DateTimeFormat("en-CA", { timeZoneName: "short" }).formatToParts(new Date()).find((p) => p.type === "timeZoneName")
                ?.value ?? ""
        );
    } catch {
        return "";
    }
})();
function dayName(ms) {
    const d = new Date(ms);
    const today = new Date();
    if (d.toDateString() === today.toDateString()) return "Today";
    const tomorrow = new Date(today.getTime() + 86_400_000);
    if (d.toDateString() === tomorrow.toDateString()) return "Tomorrow";
    return d.toLocaleDateString("en-CA", { weekday: "short", month: "short", day: "numeric" });
}

function logo(c, size = "") {
    const box = document.createElement("span");
    box.className = `logo ${size}`.trim();
    box.setAttribute("aria-hidden", "true");
    box.textContent = (c.name.match(/[\p{L}\p{N}]/u)?.[0] ?? "•").toUpperCase();
    const src = logoSrc(c.logo, location.protocol);
    if (src) {
        const img = document.createElement("img");
        img.alt = "";
        img.loading = "lazy";
        img.decoding = "async";
        img.referrerPolicy = "no-referrer";
        img.src = src;
        img.onerror = () => img.remove();
        box.append(img);
    }
    return box;
}

/** Words for a screen reader only, so a button's name still starts with the text it shows (WCAG 2.5.3). */
function spoken(text) {
    const span = document.createElement("span");
    span.className = "visually-hidden";
    span.textContent = text;
    return span;
}

function channelLabel(c) {
    const box = document.createElement("span");
    box.className = "ch-text";
    if (c.number) {
        const n = document.createElement("span");
        n.className = "ch-number";
        n.textContent = c.number;
        box.append(n);
    }
    const name = document.createElement("span");
    name.className = "ch-name";
    name.textContent = c.name;
    box.append(name);
    return box;
}

/** A programme's details, and what to do with its channel. */
function showDetails(raw, p) {
    const c = edits.display(raw);
    const now = Date.now();
    $("show-logo").replaceChildren(logo(c));
    $("show-channel").textContent = c.number ? `${c.number}  ${c.name}` : c.name;
    $("show-title").textContent = p ? p.title || "Untitled" : c.name;
    const kind = p ? genreName(genreOf(p)) : "";
    const when = p
        ? `${p.start <= now && p.stop > now ? "On now · " : `${dayName(p.start)} · `}${time(p.start)} to ${time(p.stop)}${kind ? ` · ${kind}` : ""}`
        : c.group;
    $("show-when").textContent = when;
    const { next } = nowNext(programmes(c), p ? p.stop - 1 : now);
    $("show-next").textContent = next && next !== p ? `Then: ${time(next.start)} ${next.title || "Untitled"}` : "";
    tell("show-message", "", "ok");
    const open = document.createElement("button");
    open.type = "button";
    open.textContent = clickPlan(c.url, platform, c.name, state.click).vlc ? `Open ${c.name} in VLC` : `Copy ${c.name}'s link`;
    open.onclick = () => play(c);
    const file = document.createElement("button");
    file.type = "button";
    file.className = "quiet";
    file.textContent = "Playlist file";
    file.setAttribute("aria-label", `Playlist file for ${c.name}`);
    file.onclick = () => download(fileName(c.name), m3uFor([c]));
    const link = document.createElement("button");
    link.type = "button";
    link.className = "quiet";
    link.textContent = "Copy link";
    link.setAttribute("aria-label", `Copy link for ${c.name}`);
    link.onclick = () => copy(c.url, "show-message", `${c.name}'s link`);
    $("show-actions").replaceChildren(open, file, link, ...edits.channelButtons(raw));
    $("show").showModal();
    open.focus();
}
edits.setShowDetailsRefresh((raw) => showDetails(raw, null));

// ----- the guide: channels down the side, time across, as the app's Live TV guide -----

/** Width of a minute, in pixels: wider screens show more time rather than bigger cells, as in the app. */
const perMinute = () => (matchMedia("(max-width: 600px)").matches ? 3 : 4);
const xOf = (t) => ((t - state.span.from) / 60_000) * perMinute();

function gridHead() {
    const { from, to } = state.span;
    const head = document.createElement("div");
    head.className = "epg-row epg-head";
    head.setAttribute("role", "row");
    const corner = document.createElement("div");
    corner.className = "epg-ch epg-corner";
    corner.setAttribute("role", "columnheader");
    corner.id = "epg-day";
    corner.textContent = `${dayName(from)}${zone ? ` · ${zone}` : ""}`;
    const times = document.createElement("div");
    times.className = "epg-track epg-times";
    times.setAttribute("role", "columnheader");
    times.style.width = `${xOf(to)}px`;
    for (const t of ticks(from, to)) {
        const label = document.createElement("span");
        label.style.left = `${xOf(t)}px`;
        const midnight = new Date(t).getHours() === 0 && new Date(t).getMinutes() === 0;
        label.textContent = midnight ? `${dayName(t)} ${time(t)}` : time(t);
        times.append(label);
    }
    head.append(corner, times);
    return head;
}

function gridRow(c, now) {
    const { from, to } = state.span;
    const row = document.createElement("div");
    row.className = "epg-row";
    row.setAttribute("role", "row");
    const cell = document.createElement("div");
    cell.className = "epg-ch";
    cell.setAttribute("role", "rowheader");
    const open = document.createElement("button");
    open.type = "button";
    open.className = "epg-open";
    open.title = playLabel(c);
    open.onclick = () => play(c);
    open.append(logo(c, "small"), channelLabel(c), spoken(`. ${playLabel(c)}`));
    cell.append(open);
    const track = document.createElement("div");
    track.className = "epg-track";
    track.setAttribute("role", "cell");
    track.style.width = `${xOf(to)}px`;
    const shows = (programmes(c) ?? []).filter((p) => p.stop > from && p.start < to);
    if (!shows.length) {
        const none = document.createElement("button");
        none.type = "button";
        none.className = "epg-show epg-none";
        none.style.left = "0";
        none.style.width = `${xOf(to)}px`;
        const text = document.createElement("span");
        text.className = "epg-label";
        text.textContent = "No guide for this channel";
        none.append(spoken(`${c.name}: `), text, spoken(`. ${playLabel(c)}`));
        none.onclick = () => play(c);
        track.append(none);
    }
    for (const p of shows) {
        const onNow = p.start <= now && p.stop > now;
        const past = p.stop <= now;
        // A programme that has ended can't be watched, so it's text, not a button.
        const block = document.createElement(past ? "div" : "button");
        if (!past) block.type = "button";
        block.className = `epg-show${onNow ? " on" : p.stop <= now ? " past" : ""}`;
        const left = Math.max(0, xOf(p.start));
        block.style.left = `${left}px`;
        block.style.width = `${Math.max(8, xOf(Math.min(p.stop, to)) - left)}px`;
        const title = document.createElement("span");
        title.className = "epg-title";
        title.textContent = p.title || "Untitled";
        const range = document.createElement("span");
        range.className = "epg-time";
        range.textContent = `${time(p.start)} to ${time(p.stop)}`;
        // The label sticks inside the guide's left edge, so a programme that began before the view keeps its title in sight.
        const label = document.createElement("span");
        label.className = "epg-label";
        label.append(spoken(`${c.name}, ${onNow ? "on now" : past ? "ended" : dayName(p.start)}: `), title, range);
        block.append(label);
        // What's on now, or still to come, opens the channel (Melvin, 2026-11-21: "click on a show or a channel ...
        // should either open the link up automatically in vlc ... or copy the link"). Details are in Now and next.
        if (!past) {
            block.title = playLabel(c);
            block.onclick = () => play(c);
        }
        track.append(block);
    }
    row.append(cell, track);
    return row;
}

function nowLine(now) {
    const line = document.createElement("span");
    line.className = "epg-now";
    line.id = "epg-now";
    line.setAttribute("aria-hidden", "true");
    line.style.left = `calc(var(--ch) + ${xOf(now)}px)`;
    return line;
}

function drawGrid(now) {
    const box = $("grid");
    const keep = box.scrollLeft;
    const table = document.createElement("div");
    table.className = "epg-table";
    table.setAttribute("role", "table");
    table.setAttribute("aria-label", "Guide");
    table.append(gridHead(), ...state.items.slice(0, state.shown).map((c) => gridRow(c, now)), nowLine(now));
    box.replaceChildren(table);
    box.scrollLeft = keep;
}

function scrollGuide(to, smooth = true) {
    const box = $("grid");
    box.scrollTo({ left: Math.max(0, xOf(to)), behavior: smooth ? "smooth" : "auto" });
}

/** The day in the corner follows what's in view. */
function followDay() {
    const corner = $("epg-day");
    if (!corner || !state.span) return;
    const at = state.span.from + ($("grid").scrollLeft / perMinute()) * 60_000;
    corner.textContent = `${dayName(at)}${zone ? ` · ${zone}` : ""}`;
}

// ----- the list: what's on now and next, as the app's Live TV list -----

function listRow(c, now) {
    const li = document.createElement("li");
    li.className = "channel";
    const open = document.createElement("button");
    open.type = "button";
    open.className = "channel-open";
    open.onclick = () => play(c);
    const text = document.createElement("span");
    text.className = "channel-text";
    const name = document.createElement("span");
    name.className = "channel-name";
    name.textContent = c.number ? `${c.number}  ${c.name}` : c.name;
    text.append(name);
    const { now: on, next } = nowNext(programmes(c), now);
    if (on) {
        const line = document.createElement("span");
        line.className = "small now";
        line.textContent = `${on.title || "Untitled"} · until ${time(on.stop)}`;
        const bar = document.createElement("span");
        bar.className = "bar";
        bar.style.setProperty("--done", `${Math.round(progress(on, now) * 100)}%`);
        text.append(line, bar);
    }
    if (next) {
        const line = document.createElement("span");
        line.className = "small";
        line.textContent = `Next: ${time(next.start)} ${next.title || "Untitled"}`;
        text.append(line);
    }
    if (!on && !next) {
        const line = document.createElement("span");
        line.className = "small";
        line.textContent = c.group || "No guide for this channel";
        text.append(line);
    }
    open.append(logo(c), text, spoken(`. ${playLabel(c)}`));
    const more = document.createElement("button");
    more.type = "button";
    more.className = "quiet";
    more.textContent = "Details";
    more.setAttribute("aria-label", `Details for ${c.name}`);
    more.onclick = () => showDetails(c, on ?? next);
    li.append(open, more);
    return li;
}

function draw() {
    const now = Date.now();
    state.items = applyFilters(state.channels.map(edits.display), state.filters, nowOf);
    const total = state.channels.length;
    const n = state.items.length;
    $("count").textContent =
        n === total
            ? `${total.toLocaleString("en-CA")} ${total === 1 ? "channel" : "channels"}`
            : `${n.toLocaleString("en-CA")} of ${total.toLocaleString("en-CA")} channels`;
    const grid = state.view === "grid";
    $("grid-box").hidden = !grid || !n;
    $("list").hidden = grid || !n;
    $("none").hidden = n > 0;
    if (grid && n) drawGrid(now);
    else if (n) $("list").replaceChildren(...state.items.slice(0, state.shown).map((c) => listRow(c, now)));
    $("more").hidden = n <= state.shown;
    drawFilterButton();
}

/** More rows as the guide nears its end, so a long playlist doesn't draw thousands of rows at once. */
function more() {
    if (state.shown >= state.items.length) return;
    state.shown += PAGE;
    draw();
}

// ---------- the filters, as the app's Live TV filters ----------

const filterKey = () => `tuned.channels.filters.${state.current?.id ?? ""}`;

function saveFilters() {
    try {
        localStorage.setItem(filterKey(), JSON.stringify({ ...state.filters, query: "" }));
    } catch {
        // Private browsing: the filters last until the page closes.
    }
}

function loadFilters() {
    try {
        const saved = JSON.parse(localStorage.getItem(filterKey()) ?? "null");
        return saved ? { ...NO_FILTERS, ...saved, query: "" } : { ...NO_FILTERS };
    } catch {
        return { ...NO_FILTERS };
    }
}

function chips(id, name, choices, chosen, onPick) {
    const box = $(id);
    const legend = box.querySelector("legend");
    const all = [{ value: "", label: "Any" }, ...choices];
    box.replaceChildren(
        legend,
        ...all.map(({ value, label }) => {
            const l = document.createElement("label");
            const input = document.createElement("input");
            input.type = "radio";
            input.name = name;
            input.value = String(value);
            input.checked = String(chosen ?? "") === String(value);
            input.onchange = () => onPick(value);
            l.append(input, label);
            return l;
        }),
    );
    box.hidden = choices.length === 0;
}

function setFilter(change) {
    state.filters = { ...state.filters, ...change };
    state.shown = PAGE;
    saveFilters();
    draw();
}

function drawFilters() {
    const offer = offers(state.channels, nowOf);
    const f = state.filters;
    // A chosen filter always shows, even if nothing has it now (what's on moves on).
    const keep = (list, key, value) =>
        value && !list.some((x) => x[key] === value)
            ? [...list, ...[TOPICS, QUALITIES, GENRES].flat().filter((x) => x[key] === value)]
            : list;
    chips(
        "f-topic",
        "topic",
        keep(offer.topics, "key", f.topic).map((t) => ({ value: t.key, label: t.label })),
        f.topic,
        (v) => setFilter({ topic: v }),
    );
    chips(
        "f-quality",
        "quality",
        keep(offer.qualities, "key", f.quality).map((q) => ({ value: q.key, label: q.label })),
        f.quality,
        (v) => setFilter({ quality: v }),
    );
    chips(
        "f-on-now",
        "on-now",
        keep(offer.genres, "code", f.onNow).map((g) => ({ value: g.code, label: g.label })),
        f.onNow || "",
        (v) => setFilter({ onNow: Number(v) || 0 }),
    );
    const countries = offer.countries;
    $("f-country").replaceChildren(
        new Option("Every country", ""),
        ...countries.map((c) => new Option(`${c.name} (${c.count.toLocaleString("en-CA")})`, c.tag)),
    );
    $("f-country").value = countries.some((c) => c.tag === f.country) ? f.country : "";
    $("f-country-field").hidden = countries.length < 2;
    $("f-guide").checked = f.withGuide;
    $("f-guide-field").hidden = !offer.guide;
}

function drawFilterButton() {
    const f = state.filters;
    const parts = [
        TOPICS.find((t) => t.key === f.topic)?.label,
        QUALITIES.find((q) => q.key === f.quality)?.label,
        f.onNow ? `${genreName(f.onNow)} on now` : "",
        f.withGuide ? "Has guide" : "",
        f.country ? $("f-country").selectedOptions[0]?.text.replace(/ \(.*\)$/, "") : "",
    ].filter(Boolean);
    const shown = parts.slice(0, 2).join(" · ");
    $("filters-button").textContent = parts.length ? `Filters: ${parts.length > 2 ? `${shown} +${parts.length - 2}` : shown}` : "Filters";
    $("filters-button").classList.toggle("chosen", parts.length > 0);
}

function drawGroups() {
    const groups = groupsOf(state.channels).filter((g) => g.name);
    $("group").replaceChildren(
        new Option("All categories", ""),
        ...groups.map((g) => new Option(`${g.name} (${g.count.toLocaleString("en-CA")})`, g.name)),
    );
    $("group").disabled = groups.length === 0;
    $("group").value = groups.some((g) => g.name === state.filters.group) ? state.filters.group : "";
    if (!$("group").value) state.filters.group = "";
}

// ---------- loading a playlist ----------

function reset() {
    state.abort?.abort();
    state.abort = new AbortController();
    state.channels = [];
    state.guide = new Map();
    state.wholeUrl = "";
    state.shown = PAGE;
    $("channels").classList.add("empty");
    $("blocked").hidden = true;
    $("grid").replaceChildren();
    $("list").replaceChildren();
    tell("pick-message", "");
    tell("source", "", "ok");
    tell("count", "", "ok");
}

/** Shows the channels: the filters this playlist had last time, and the guide at now. */
function show(channels, guide) {
    for (const c of channels) c.hasGuide = Boolean(c.epgId && guide.has(c.epgId));
    state.channels = channels;
    state.guide = guide;
    state.span = guideSpan(guide, Date.now());
    state.filters = loadFilters();
    $("search").value = "";
    tell("status", "");
    $("channels").classList.remove("empty");
    drawGroups();
    drawFilters();
    draw();
    requestAnimationFrame(() => scrollGuide(Date.now() - 30 * 60_000, false));
}

async function open(p) {
    reset();
    const id = ++state.loadId;
    state.current = p;
    const url = new URL(location.href);
    url.searchParams.set("playlist", p.id);
    history.replaceState(null, "", url);
    drawWhole();
    if (p.kind === "stalker") {
        tell("status", "A MAC portal only works in the Tuned app: a web page or VLC can't sign in to it as a set-top box does.", "error");
        return;
    }
    tell("status", "Getting your channels and guide from your account…", "ok");
    // The login and the app's copy at once: both are on our server.
    const [login, copy] = await Promise.all([
        p.kind === "file" ? Promise.resolve({ status: "app only" }) : getJson(`/api/account/check?id=${encodeURIComponent(p.id)}`),
        appCopy(p.id),
    ]);
    if (id !== state.loadId) return;
    const hasLogin = login.status === "ok";
    if (hasLogin)
        state.wholeUrl =
            login.kind === "xtream"
                ? xtreamPlaylistUrl(parseServer(login.server)?.base ?? login.server, login.username, login.password)
                : login.link;
    drawWhole();
    const fromApp = copy.body ? fromAppCopy(copy.body, hasLogin ? login : null) : null;
    // The app's copy, however old: the provider's own list usually can't be read from an https page.
    if (fromApp?.channels.length) return showAppCopy(fromApp, copy.by);
    // No copy in the account: say so plainly, and why, rather than leaving an empty page (Melvin, 2026-11-21: "if not,
    // it should tell me in the UI").
    const missing = noCopyText(copy.missing ?? (copy.body ? "empty" : "none"));
    if (!hasLogin) {
        const why = {
            "signed out": "You're signed out. Sign in again on your account page.",
            "too many": "Too many playlists opened this hour. Try again later.",
        };
        if (why[login.status]) tell("status", why[login.status]);
        else
            showNoCopy(
                missing,
                login.status === "app only" ? "" : "This playlist's login couldn't be read, so the page can't ask your provider either.",
            );
        return;
    }
    tell("status", `${missing.title}. Asking your provider instead…`, "ok");
    const read = login.kind === "xtream" ? await readXtream(login) : await readLink(login);
    if (id !== state.loadId || read.error === "stopped") return;
    if (read.error) {
        tell("status", "");
        showNoCopy(
            missing,
            `Asking your provider instead didn't work: ${
                read.error === "empty" ? "its playlist came back empty." : lowerFirst(reasons[read.error] ?? reasons.unreachable)
            }`,
        );
        return;
    }
    tell("source", "Straight from your provider.", "ok");
    showNoCopy(missing, "Meanwhile, the channels above come straight from your provider, which this browser could read.");
    show(read.channels, new Map());
    loadGuide(read.guides, id);
}

/**
 * The copy the Tuned app keeps in the account (docs/decisions/web.md › The app's copy) as `{ body, by }`, or
 * `{ missing }` saying why there's none: "none" (the app never saved one), "too many", or "error".
 */
async function appCopy(playlistId) {
    try {
        const res = await fetch(`/api/account/channels?playlist=${encodeURIComponent(playlistId)}`, {
            cache: "no-store",
            credentials: "same-origin",
        });
        if (!res.ok) {
            const status = (await res.json().catch(() => null))?.status;
            return { missing: res.status === 404 && status === "none" ? "none" : status === "too many" ? "too many" : "error" };
        }
        return { body: await res.json(), by: decodeURIComponent(res.headers.get("x-saved-by") ?? "") };
    } catch {
        return { missing: "error" };
    }
}

/** The card that says this playlist has no channels in the account yet, why, and what to do. */
function showNoCopy(missing, extra) {
    $("blocked-title").textContent = missing.title;
    $("blocked-why").textContent = missing.why;
    $("blocked-fix").textContent = missing.fix;
    $("blocked-extra").textContent = extra ?? "";
    $("blocked").hidden = false;
}

function lowerFirst(text) {
    return text ? text[0].toLowerCase() + text.slice(1) : text;
}

function showAppCopy(copy, by) {
    const from = by ? `your Tuned app on ${by}` : "your Tuned app";
    const when = copy.savedAt ? `, ${ago(copy.savedAt)}` : "";
    const stale = copy.savedAt && Date.now() - copy.savedAt > 24 * HOUR ? " Open Tuned to bring it up to date." : "";
    const guide = copy.guide.size ? ` Guide for ${copy.guide.size.toLocaleString("en-CA")} channels.` : " No guide came with it.";
    tell("source", `From ${from}${when}.${guide}${stale}`, "ok");
    show(copy.channels, copy.guide);
}

async function loadGuide(guides, id) {
    const urls = [...new Set(guides.filter(Boolean))];
    const ids = new Set(state.channels.map((c) => c.epgId).filter(Boolean));
    if (!urls.length || !ids.size) {
        tell(
            "source",
            urls.length
                ? "Straight from your provider. These channels don't name a guide."
                : "Straight from your provider, which has no guide.",
            "ok",
        );
        return;
    }
    const now = Date.now();
    const reader = new GuideReader(ids, now - GUIDE_BEFORE_MS, now + GUIDE_AFTER_MS);
    tell("source", "Reading the guide…", "ok");
    let read = 0;
    for (const url of urls) {
        const ok = await readGuide(url, reader, (n) => {
            if (id === state.loadId) tell("source", `Reading the guide… ${n.toLocaleString("en-CA")} programmes so far`, "ok");
        });
        if (id !== state.loadId) return;
        if (ok) read++;
    }
    const guide = reader.done();
    if (!read) tell("source", "Your provider's guide couldn't be read from a web page, so there's no guide.");
    else tell("source", `Straight from your provider. Guide for ${guide.size.toLocaleString("en-CA")} channels.`, "ok");
    const keep = state.filters;
    show(state.channels, guide);
    state.filters = keep;
    draw();
}

// ---------- start ----------

let typing = 0;
$("search").oninput = () => {
    clearTimeout(typing);
    typing = setTimeout(() => setFilter({ query: $("search").value }), 150);
};
$("group").onchange = () => setFilter({ group: $("group").value });
$("f-country").onchange = () => setFilter({ country: $("f-country").value });
$("f-guide").onchange = () => setFilter({ withGuide: $("f-guide").checked });
$("filters-clear").onclick = () => {
    state.filters = { ...NO_FILTERS, query: state.filters.query };
    saveFilters();
    $("group").value = "";
    drawFilters();
    draw();
};
enhanceSelect($("playlist"));
enhanceSelect($("group"));
enhanceSelect($("f-country"));
$("filters-button").onclick = () => {
    const open = $("filters").hidden;
    $("filters").hidden = !open;
    $("filters-button").setAttribute("aria-expanded", String(open));
};
setUpClick();
$("more").onclick = more;
$("earlier").onclick = () => ($("grid").scrollLeft -= 2 * 60 * perMinute());
$("later").onclick = () => ($("grid").scrollLeft += 2 * 60 * perMinute());
$("to-now").onclick = () => scrollGuide(Date.now() - 30 * 60_000);
$("grid").addEventListener("scroll", () => {
    requestAnimationFrame(followDay);
    const box = $("grid");
    if (box.scrollTop + box.clientHeight > box.scrollHeight - 400) more();
});
for (const radio of document.querySelectorAll('input[name="view"]'))
    radio.onchange = () => {
        state.view = radio.value;
        state.shown = PAGE;
        draw();
        if (state.view === "grid") requestAnimationFrame(() => scrollGuide(Date.now() - 30 * 60_000, false));
    };
$("playlist").onchange = () => {
    const p = state.playlists.find((x) => x.id === $("playlist").value);
    if (p) open(p);
};
// What's on now moves on by itself, but only while someone is looking.
setInterval(() => {
    if (document.hidden || !state.channels.length) return;
    if (state.view === "grid" && $("epg-now")) $("epg-now").style.left = `calc(var(--ch) + ${xOf(Date.now())}px)`;
    else draw();
}, 60_000);

async function start() {
    const library = await getJson("/api/account/library");
    if (library.status === "signed out" || library.http === 401) {
        $("signed-out").hidden = false;
        return;
    }
    if (library.status !== "ok") {
        tell("status", "Something went wrong on our side. Try again in a moment.");
        return;
    }
    document.querySelector("main").classList.add("signed-in");
    $("account-nav").hidden = false;
    drawNav($("account-nav"), "channels");
    $("signed-in").hidden = false;
    edits.takeLibrary(library);
    enhanceSelect($("profile"));
    state.playlists = library.playlists ?? [];
    if (!state.playlists.length) {
        tell("status", "Your account has no playlists yet. Add one on the Playlists page or in the Tuned app.", "ok");
        $("channels").hidden = true;
        $("whole-card").hidden = true;
        return;
    }
    $("playlist").replaceChildren(
        ...state.playlists.map((p) => new Option(`${p.name || p.detail || kindNames[p.kind]} (${kindNames[p.kind]})`, p.id)),
    );
    const asked = new URL(location.href).searchParams.get("playlist");
    const first =
        state.playlists.find((p) => p.id === asked) ??
        state.playlists.find((p) => p.kind === "xtream" || p.kind === "link") ??
        state.playlists[0];
    $("playlist").value = first.id;
    open(first);
}
start();
