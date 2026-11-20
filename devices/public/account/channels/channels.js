// The channel list and guide (docs/decisions/web.md › The channel list; Melvin, 2026-11-20: "show me all the channels
// and have a link that allows me to open up vlc or some other player? include an epg"). Behind the web_player flag.
// The signed-in browser gets one saved playlist's login from /api/account/check, then reads the channel list and guide
// straight from the provider, as the provider check does. A channel opens in VLC or another player on this device,
// which connects to the provider itself: this page never plays or fetches a stream, and nothing passes through Tuned.
// Links are never shown on the page (they carry the login); they're only copied or handed to the player.

import { drawNav } from "/account/nav.js";
import { access, httpsVariant, isMixed, isPreviewHost, parseServer } from "/check/verdict.mjs";
import {
    filterChannels,
    fileName,
    gridPlace,
    gridSlots,
    groupsOf,
    GuideReader,
    headerGuide,
    m3uFor,
    nowNext,
    parseM3u,
    platformOf,
    progress,
    vlcLink,
    xtreamChannels,
    xtreamGuideUrl,
    xtreamPlaylistUrl,
    ago,
    fromAppCopy,
} from "/account/channels/core.mjs";

const $ = (id) => document.getElementById(id);
const LIST_TIMEOUT_MS = 30_000;
const GUIDE_TIMEOUT_MS = 120_000;
const PAGE = 150;
const GRID_PAGE = 40;
const GUIDE_BEFORE_MS = 3_600_000;
const GUIDE_AFTER_MS = 12 * 3_600_000;
/** The app sends its copy at least every 6 hours while it's used; older than a day, the provider is asked first. */
const COPY_FRESH_MS = 24 * 3_600_000;
const platform = platformOf(navigator.userAgent, navigator.maxTouchPoints);

const state = {
    playlists: [],
    current: null, // the playlist view from /api/account/library
    channels: [],
    guide: new Map(),
    wholeUrl: "",
    shown: PAGE,
    view: "list",
    loadId: 0,
    abort: null,
};

const kindNames = { xtream: "Xtream login", link: "M3U link", stalker: "MAC portal", file: "File" };

function tell(id, text, tone = "error") {
    const line = $(id);
    line.textContent = text ?? "";
    line.dataset.tone = tone;
}

// ---------- the flag and the account ----------

/** Production follows the admin's web_player flag; preview deployments and localhost always show the page. */
async function enabled() {
    if (isPreviewHost(location.hostname)) return true;
    try {
        const res = await fetch("/api/flags", { cache: "no-store" });
        return (await res.json())?.flags?.web_player === true;
    } catch {
        return false;
    }
}

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

// ---------- links for the player ----------

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

async function copy(text, where, what) {
    try {
        await navigator.clipboard.writeText(text);
        tell(where, `${what} copied. In VLC: Media › Open Network Stream, then paste.`, "ok");
    } catch {
        tell(where, "This browser didn't allow copying. Use the playlist file instead.");
    }
}

function drawWhole() {
    const url = state.wholeUrl;
    const name = state.current?.name || state.current?.detail || "Tuned playlist";
    const vlc = url ? vlcLink(url, platform, name) : null;
    $("whole-vlc").hidden = !vlc;
    if (vlc) $("whole-vlc").href = vlc;
    $("whole-file").disabled = !url;
    $("whole-copy").disabled = !url;
    // A playlist whose one entry is the provider's own playlist link: VLC opens it and reads the whole list itself.
    $("whole-file").onclick = () => download(fileName(name), m3uFor([{ name, url }]));
    $("whole-copy").onclick = () => copy(url, "pick-message", "Playlist link");
}

function channelActions(c) {
    const box = document.createElement("div");
    box.className = "row-actions";
    const vlc = vlcLink(c.url, platform, c.name);
    if (vlc) {
        const open = document.createElement("a");
        open.className = "button";
        open.href = vlc;
        open.textContent = "Open in VLC";
        open.setAttribute("aria-label", `Open ${c.name} in VLC`);
        box.append(open);
    }
    const file = document.createElement("button");
    file.type = "button";
    file.className = vlc ? "quiet" : "";
    file.textContent = vlc ? "Playlist file" : "Play";
    file.title = "A playlist file for this channel, which opens in VLC or your usual player";
    file.setAttribute("aria-label", vlc ? `Playlist file for ${c.name}` : `Play ${c.name} in VLC or your usual player`);
    file.onclick = () => download(fileName(c.name), m3uFor([c]));
    const link = document.createElement("button");
    link.type = "button";
    link.className = "quiet";
    link.textContent = "Copy link";
    link.setAttribute("aria-label", `Copy link for ${c.name}`);
    link.onclick = () => copy(c.url, "pick-message", `${c.name}'s link`);
    box.append(file, link);
    return box;
}

// ---------- drawing ----------

const time = (ms) => new Date(ms).toLocaleTimeString("en-CA", { hour: "numeric", minute: "2-digit" });
const programmes = (c) => (c.epgId ? state.guide.get(c.epgId) : null) ?? null;
const nowTitle = (c) => nowNext(programmes(c), Date.now()).now?.title ?? "";

function logo(c) {
    const box = document.createElement("span");
    box.className = "logo";
    box.setAttribute("aria-hidden", "true");
    box.textContent = (c.name.match(/[\p{L}\p{N}]/u)?.[0] ?? "•").toUpperCase();
    if (c.logo && /^https?:\/\//i.test(c.logo)) {
        const img = document.createElement("img");
        img.alt = "";
        img.loading = "lazy";
        img.decoding = "async";
        img.referrerPolicy = "no-referrer";
        img.src = c.logo;
        img.onerror = () => img.remove();
        box.append(img);
    }
    return box;
}

function filtered() {
    return filterChannels(state.channels, { group: $("group").value, query: $("search").value, nowTitle });
}

function listRow(c, now) {
    const li = document.createElement("li");
    li.className = "channel";
    const text = document.createElement("div");
    text.className = "channel-text";
    const name = document.createElement("div");
    name.className = "channel-name";
    name.textContent = c.number ? `${c.number}  ${c.name}` : c.name;
    text.append(name);
    const { now: on, next } = nowNext(programmes(c), now);
    if (on) {
        const line = document.createElement("p");
        line.className = "small now";
        line.textContent = `Now: ${on.title || "Untitled"} · until ${time(on.stop)}`;
        if (on.desc) line.title = on.desc;
        const bar = document.createElement("span");
        bar.className = "bar";
        bar.setAttribute("role", "img");
        const pct = Math.round(progress(on, now) * 100);
        bar.setAttribute("aria-label", `${pct}% through`);
        bar.style.setProperty("--done", `${pct}%`);
        text.append(line, bar);
    }
    if (next) {
        const line = document.createElement("p");
        line.className = "small";
        line.textContent = `Next: ${time(next.start)} ${next.title || "Untitled"}`;
        text.append(line);
    }
    if (!on && !next && state.guide.size) {
        const line = document.createElement("p");
        line.className = "small";
        line.textContent = c.group || "No guide for this channel";
        text.append(line);
    } else if (!state.guide.size && c.group) {
        const line = document.createElement("p");
        line.className = "small";
        line.textContent = c.group;
        text.append(line);
    }
    li.append(logo(c), text, channelActions(c));
    return li;
}

function drawList(items, now) {
    $("list").replaceChildren(...items.slice(0, state.shown).map((c) => listRow(c, now)));
}

function drawGrid(items, now) {
    const slots = gridSlots(now, 3);
    const from = slots[0];
    const to = from + slots.length * 1_800_000;
    const grid = document.createElement("div");
    grid.className = "guide-grid";
    grid.setAttribute("role", "table");
    grid.setAttribute("aria-label", "Guide for the next three hours");
    const head = document.createElement("div");
    head.className = "grid-row grid-head";
    head.setAttribute("role", "row");
    const corner = document.createElement("div");
    corner.className = "grid-channel";
    corner.setAttribute("role", "columnheader");
    corner.textContent = "Channel";
    const times = document.createElement("div");
    times.className = "grid-times";
    times.setAttribute("role", "columnheader");
    for (const s of slots) {
        const t = document.createElement("span");
        t.textContent = time(s);
        times.append(t);
    }
    const nowLine = document.createElement("span");
    nowLine.className = "now-line";
    nowLine.style.left = `${((now - from) / (to - from)) * 100}%`;
    nowLine.setAttribute("aria-hidden", "true");
    times.append(nowLine);
    head.append(corner, times);
    grid.append(head);
    for (const c of items.slice(0, state.shown)) {
        const row = document.createElement("div");
        row.className = "grid-row";
        row.setAttribute("role", "row");
        const cell = document.createElement("div");
        cell.className = "grid-channel";
        cell.setAttribute("role", "rowheader");
        const vlc = vlcLink(c.url, platform, c.name);
        const open = document.createElement(vlc ? "a" : "button");
        open.className = "grid-open";
        if (vlc) open.href = vlc;
        else {
            open.type = "button";
            open.onclick = () => download(fileName(c.name), m3uFor([c]));
        }
        open.setAttribute("aria-label", vlc ? `Open ${c.name} in VLC` : `Play ${c.name} in VLC or your usual player`);
        open.append(logo(c));
        const label = document.createElement("span");
        label.textContent = c.name;
        open.append(label);
        cell.append(open);
        const track = document.createElement("div");
        track.className = "grid-track";
        track.setAttribute("role", "cell");
        const shows = (programmes(c) ?? []).map((p) => [p, gridPlace(p, from, to)]).filter(([, at]) => at);
        if (!shows.length) {
            const none = document.createElement("span");
            none.className = "grid-none small";
            none.textContent = "No guide";
            track.append(none);
        }
        for (const [p, at] of shows) {
            const block = document.createElement("span");
            block.className = "grid-show";
            if (p.start <= now && p.stop > now) block.classList.add("on");
            block.style.left = `${at.left * 100}%`;
            block.style.width = `${at.width * 100}%`;
            block.textContent = p.title || "Untitled";
            block.title = `${time(p.start)} to ${time(p.stop)}  ${p.title}${p.desc ? `\n${p.desc}` : ""}`;
            track.append(block);
        }
        row.append(cell, track);
        grid.append(row);
    }
    $("grid").replaceChildren(grid);
}

function draw() {
    const items = filtered();
    const now = Date.now();
    const total = state.channels.length;
    $("count").textContent =
        items.length === total
            ? `${total.toLocaleString("en-CA")} ${total === 1 ? "channel" : "channels"}`
            : `${items.length.toLocaleString("en-CA")} of ${total.toLocaleString("en-CA")} channels`;
    const grid = state.view === "grid";
    $("list").hidden = grid;
    $("grid").hidden = !grid;
    if (grid) drawGrid(items, now);
    else drawList(items, now);
    $("more").hidden = items.length <= state.shown;
}

function drawGroups() {
    const groups = groupsOf(state.channels).filter((g) => g.name);
    $("group").replaceChildren(
        new Option(`All groups`, ""),
        ...groups.map((g) => new Option(`${g.name} (${g.count.toLocaleString("en-CA")})`, g.name)),
    );
    $("group").disabled = groups.length === 0;
}

// ---------- loading a playlist ----------

function reset() {
    state.abort?.abort();
    state.abort = new AbortController();
    state.channels = [];
    state.guide = new Map();
    state.wholeUrl = "";
    state.shown = state.view === "grid" ? GRID_PAGE : PAGE;
    $("channels").hidden = true;
    $("blocked").hidden = true;
    tell("pick-message", "");
    tell("guide-line", "", "ok");
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
    tell("status", "Getting this playlist from your account…", "ok");
    // The login and the app's copy at once: the copy is on our server, so it's usually there first.
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
    const fromApp = copy ? fromAppCopy(copy.body, hasLogin ? login : null) : null;
    const fresh = fromApp?.savedAt && Date.now() - fromApp.savedAt < COPY_FRESH_MS;
    // A fresh copy from the app opens at once and needs nothing from the provider.
    if (fromApp?.channels.length && (fresh || !hasLogin)) return showAppCopy(fromApp, copy.by);
    if (!hasLogin) {
        const why = {
            "signed out": "You're signed out. Sign in again on your account page.",
            "too many": "Too many playlists opened this hour. Try again later.",
            "app only": "Open this playlist in the Tuned app once, signed in to your account, and its channels show up here.",
        };
        tell("status", why[login.status] ?? "This playlist couldn't be opened. Reload the page and try again.");
        return;
    }
    tell("status", "Asking your provider for its channel list…", "ok");
    const read = login.kind === "xtream" ? await readXtream(login) : await readLink(login);
    if (id !== state.loadId || read.error === "stopped") return;
    if (read.error) {
        // The provider doesn't let web pages read it: the app's copy, however old, is better than nothing.
        if (fromApp?.channels.length) return showAppCopy(fromApp, copy.by);
        tell("status", "");
        $("blocked-why").textContent =
            read.error === "empty" ? "Your provider's playlist came back empty." : (reasons[read.error] ?? reasons.unreachable);
        $("blocked-setting").hidden = read.error !== "insecure";
        $("blocked").hidden = false;
        return;
    }
    state.channels = read.channels;
    tell("status", "");
    drawGroups();
    $("channels").hidden = false;
    draw();
    loadGuide(read.guides, id);
}

/** The copy the Tuned app keeps in the account (docs/decisions/web.md › The app's copy), or null when there's none. */
async function appCopy(playlistId) {
    try {
        const res = await fetch(`/api/account/channels?playlist=${encodeURIComponent(playlistId)}`, {
            cache: "no-store",
            credentials: "same-origin",
        });
        if (!res.ok) return null;
        return { body: await res.json(), by: decodeURIComponent(res.headers.get("x-saved-by") ?? "") };
    } catch {
        return null;
    }
}

function showAppCopy(copy, by) {
    state.channels = copy.channels;
    state.guide = copy.guide;
    tell("status", "");
    const from = by ? `your Tuned app on ${by}` : "your Tuned app";
    const when = copy.savedAt ? `, ${ago(copy.savedAt)}` : "";
    const guide = copy.guide.size ? ` Guide for ${copy.guide.size.toLocaleString("en-CA")} channels.` : " No guide came with it.";
    tell("guide-line", `From ${from}${when}.${guide}`, "ok");
    drawGroups();
    $("channels").hidden = false;
    draw();
}

async function loadGuide(guides, id) {
    const urls = [...new Set(guides.filter(Boolean))];
    const ids = new Set(state.channels.map((c) => c.epgId).filter(Boolean));
    if (!urls.length || !ids.size) {
        tell("guide-line", urls.length ? "These channels don't name a guide." : "This playlist has no guide.", "ok");
        return;
    }
    const now = Date.now();
    const reader = new GuideReader(ids, now - GUIDE_BEFORE_MS, now + GUIDE_AFTER_MS);
    tell("guide-line", "Reading the guide…", "ok");
    let read = 0;
    for (const url of urls) {
        const ok = await readGuide(url, reader, (n) => {
            if (id === state.loadId) tell("guide-line", `Reading the guide… ${n.toLocaleString("en-CA")} programmes so far`, "ok");
        });
        if (id !== state.loadId) return;
        if (ok) read++;
    }
    state.guide = reader.done();
    if (!read) tell("guide-line", "Your provider's guide couldn't be read from a web page, so the list has no guide.");
    else if (!state.guide.size) tell("guide-line", "The guide has nothing for these channels in the next 12 hours.", "ok");
    else tell("guide-line", `Guide for the next 12 hours, ${state.guide.size.toLocaleString("en-CA")} channels.`, "ok");
    draw();
}

// ---------- start ----------

$("search").oninput = () => {
    state.shown = state.view === "grid" ? GRID_PAGE : PAGE;
    draw();
};
$("group").onchange = $("search").oninput;
$("more").onclick = () => {
    state.shown += state.view === "grid" ? GRID_PAGE : PAGE;
    draw();
};
for (const radio of document.querySelectorAll('input[name="view"]'))
    radio.onchange = () => {
        state.view = radio.value;
        state.shown = state.view === "grid" ? GRID_PAGE : PAGE;
        draw();
    };
$("playlist").onchange = () => {
    const p = state.playlists.find((x) => x.id === $("playlist").value);
    if (p) open(p);
};
// What's on now moves on by itself, but only while someone is looking.
setInterval(() => {
    if (!document.hidden && state.channels.length) draw();
}, 60_000);

async function start() {
    const [on, library] = await Promise.all([enabled(), getJson("/api/account/library")]);
    if (library.status === "signed out" || library.http === 401) {
        $("signed-out").hidden = false;
        return;
    }
    if (!on) {
        $("off").hidden = false;
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
    state.playlists = library.playlists ?? [];
    if (!state.playlists.length) {
        tell("status", "Your account has no playlists yet. Add one on the Playlists page or in the Tuned app.", "ok");
        $("pick").hidden = true;
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
