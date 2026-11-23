// The provider check (docs/decisions/web.md): Phase 0 of the web player. Everything runs in this browser; the
// address and login stay in memory and are never sent to Tuned. One stream at a time, closed before the next opens.
// Signed in, it keeps the account side menu (Melvin, 2026-11-23).
import { drawNav } from "/account/nav.js";
import { enhanceSelect } from "/select.js";
import {
    access,
    anywayTries,
    apiUrl,
    canTryAnyway,
    codecMime,
    codecName,
    containerMime,
    httpsVariant,
    isMixed,
    liveUrl,
    looksLikePortal,
    movieUrl,
    parseM3u,
    parseServer,
    playlistUrl,
    isPreviewHost,
    redact,
    streamKind,
    verdict,
} from "./verdict.mjs";

const $ = (id) => document.getElementById(id);
const TIMEOUT_MS = 12_000;
const ua = navigator.userAgent;
const safari = /^((?!chrome|chromium|android|crios|fxios|edg).)*safari/i.test(ua);
const browser = /edg\//i.test(ua)
    ? "Edge"
    : /firefox|fxios/i.test(ua)
      ? "Firefox"
      : safari
        ? "Safari"
        : /chrome|crios/i.test(ua)
          ? "Chrome"
          : "this browser";

let secrets = [];
let facts = {};
let report = [];
let session = null; // { base, username, password } or { entries }
let login = null; // the Xtream login being checked, kept for "Try playing anyway" when its lists can't be read
let stopCurrent = () => {};

// ---------- the flag ----------

/** Production follows the admin's web_player flag; preview deployments (signed in to Vercel) and localhost always show it. */
async function enabled() {
    if (isPreviewHost(location.hostname)) return true;
    try {
        const res = await fetch("/api/flags", { cache: "no-store" });
        return (await res.json())?.flags?.web_player === true;
    } catch {
        return false;
    }
}

// ---------- small helpers ----------

function step(list, kind, text) {
    const li = document.createElement("li");
    li.className = kind;
    li.textContent = redact(text, ...secrets);
    $(list).append(li);
    report.push(`${{ ok: "OK  ", warn: "WARN", bad: "FAIL", info: "    " }[kind]} ${redact(text, ...secrets)}`);
    updateSummary();
}

async function timed(url, init = {}) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
    try {
        return await fetch(url, { ...init, signal: ctrl.signal, cache: "no-store", credentials: "omit", referrerPolicy: "no-referrer" });
    } finally {
        clearTimeout(timer);
    }
}

/** A normal request, and when it fails an opaque one, to tell a missing CORS answer from a server that isn't there. */
async function probe(url) {
    try {
        const res = await timed(url);
        return { how: "ok", res };
    } catch {
        let opaque = false;
        try {
            await timed(url, { mode: "no-cors" });
            opaque = true;
        } catch {
            opaque = false;
        }
        return { how: access({ cors: false, opaque, mixed: isMixed(url, location.protocol), insecureAllowed: facts.insecureAllowed }) };
    }
}

/** Whether this browser lets this https page load http: an opaque request to our own http address, which redirects to https. */
async function insecureAllowed() {
    if (location.protocol !== "https:") return true;
    try {
        await timed(`http://${location.host}/check/ping.txt`, { mode: "no-cors" });
        return true;
    } catch {
        return false;
    }
}

function why(how) {
    return {
        insecure: `${browser} blocked it because the address is http and this page is https.`,
        cors: "The provider answered, but doesn't allow web pages to read it (no CORS).",
        unreachable: "No answer from the provider (wrong address, it's down, or it blocks this network).",
    }[how];
}

// ---------- your saved playlists ----------
// Melvin, 2026-11-20: "if you're logged in, /check should just let you choose the playlist to try". The account's
// playlists come from the backup (/api/account/library, never their logins); the one picked has its login fetched
// from /api/account/check and kept in memory here, like a typed one. Tuned's server still never contacts the provider.

const KIND_NAMES = {
    xtream: "Xtream login",
    link: "Playlist link",
    stalker: "MAC portal",
    plex: "Plex",
    jellyfin: "Jellyfin",
    emby: "Emby",
    file: "File",
};

/** Draws the account's playlists, or says how to sign in. True when there is one this browser can check. */
async function loadSaved() {
    let answer = null;
    try {
        const res = await fetch("/api/account/library", { cache: "no-store" });
        answer = res.status === 401 ? { status: "signed out" } : await res.json();
    } catch {
        answer = null;
    }
    if (answer?.status === "signed out") $("sign-in-line").hidden = false;
    // Melvin, 2026-11-23: signed in, keep the account left side navigation on /check.
    if (answer?.status === "ok") {
        document.querySelector("main").classList.add("signed-in");
        drawNav($("account-nav"), "check");
        $("account-nav").hidden = false;
    }
    const playlists = answer?.status === "ok" ? answer.playlists : [];
    if (!playlists.length) return false;
    const wanted = new URLSearchParams(location.search).get("playlist");
    const usable = (p) => p.kind === "xtream" || p.kind === "link";
    const first = playlists.find((p) => usable(p) && (!wanted || p.id === wanted)) ?? playlists.find(usable);
    $("saved-list").replaceChildren(
        ...playlists.map((p) => {
            const label = document.createElement("label");
            const input = Object.assign(document.createElement("input"), { type: "radio", name: "saved", value: p.id });
            input.disabled = !usable(p);
            input.checked = p === first;
            const text = document.createElement("span");
            const name = Object.assign(document.createElement("span"), { className: "name", textContent: p.name || p.detail });
            const line = Object.assign(document.createElement("span"), { className: "small" });
            const parts = [KIND_NAMES[p.kind] ?? p.kind];
            if (p.name && p.detail) parts.push(p.detail);
            line.textContent = parts.join(" · ");
            text.append(name, line);
            if (!usable(p)) {
                // Melvin, 2026-11-20: "Mention that this doesn't work in the browser".
                const note = Object.assign(document.createElement("span"), { className: "small not-here" });
                note.textContent =
                    p.kind === "stalker"
                        ? "Doesn't work in a browser: a MAC portal only works in the Tuned app."
                        : "Doesn't work in a browser: this file is only on the device it came from.";
                text.append(note);
            }
            label.append(input, text);
            return label;
        }),
    );
    $("saved-check").disabled = !first;
    $("saved-card").hidden = false;
    // Melvin, 2026-11-20: the signed-out wording ("never sees your login") isn't true for a saved playlist.
    $("privacy-line").textContent =
        "It runs only in this browser. A saved playlist's login comes from your Tuned account to this browser for the check; " +
        "an address you type never reaches Tuned. Tuned's server never contacts your provider, and nothing is saved.";
    return Boolean(first) && wanted === first?.id;
}

async function checkSaved(id) {
    let answer = null;
    try {
        const res = await fetch(`/api/account/check?id=${encodeURIComponent(id)}`, { cache: "no-store" });
        answer = await res.json();
    } catch {
        answer = null;
    }
    if (answer?.status !== "ok") {
        const why = {
            "signed out": "You're signed out. Sign in again to check your playlists.",
            gone: "That playlist isn't in your account any more. Reload the page.",
            "app only": "A browser can't use this playlist. The Tuned app can.",
            "too many": "Too many checks this hour. Try again later.",
        };
        $("saved-message").textContent = why[answer?.status] ?? "Couldn't get that playlist from your account. Try again.";
        return;
    }
    $("saved-message").textContent = "";
    run(async () => {
        if (answer.kind === "xtream") {
            const server = parseServer(answer.server);
            if (!server) return step("steps", "bad", "The saved server address isn't one a browser can use.");
            await checkXtream(server, answer.username, answer.password);
        } else {
            await checkLink(answer.link);
            // A saved link usually carries its login: keep the whole link out of the summary.
            if (answer.link.length >= 8) secrets.push(answer.link);
        }
    });
}

// ---------- the account and its lists ----------

async function checkXtream(server, username, password) {
    secrets = password && password.length >= 4 ? [password] : [];
    login = { base: server.base, username, password };
    const page = location.protocol;
    const candidates = [];
    if (server.protocol === "http") candidates.push(httpsVariant(server, null));
    candidates.push(server.base);
    step("steps", "info", `Your address uses ${server.protocol}. This page is ${page.replace(":", "")}.`);

    let found = null;
    let lastHow = null;
    for (const base of candidates) {
        const label = base.startsWith("https") ? "https" : "http";
        const { how, res } = await probe(apiUrl(base, username, password));
        if (how === "ok") {
            let json = null;
            try {
                json = await res.json();
            } catch {
                json = null;
            }
            if (!json?.user_info) {
                step("steps", "bad", `The provider answered on ${label}, but not like an Xtream server.`);
                lastHow = "unreachable";
                continue;
            }
            step("steps", "ok", `The provider answers on ${label} and allows web pages (CORS).`);
            found = { base, json };
            break;
        }
        lastHow = how;
        const fallback = label === "https" && candidates.length > 1;
        step(
            "steps",
            fallback ? "info" : "bad",
            fallback ? "No https address that a web page can use; trying http." : `On ${label}: ${why(how)}`,
        );
    }
    if (!found) {
        facts.api = lastHow;
        return;
    }
    facts.api = "ok";
    facts.needsInsecure = isMixed(found.base, page);
    const info = found.json.user_info;
    if (String(info.auth) === "0") {
        facts.login = "wrong";
        step("steps", "bad", "The provider says the username or password is wrong.");
        return;
    }
    facts.login = "ok";
    const exp = Number(info.exp_date) ? new Date(Number(info.exp_date) * 1000).toLocaleDateString("en-CA") : "no end date";
    step(
        "steps",
        "ok",
        `Signed in. Account ${info.status ?? "status unknown"}, ${exp}, ${info.active_cons ?? "?"} of ${info.max_connections ?? "?"} connections in use.`,
    );
    if (Number(info.active_cons) >= Number(info.max_connections) && Number(info.max_connections) > 0)
        step("steps", "warn", "Every connection is in use. Stop playback elsewhere before trying a channel.");

    // An https port the provider names, when we only got in over http.
    if (facts.needsInsecure) {
        const twin = httpsVariant(server, found.json.server_info);
        if (twin && twin !== candidates[0]) {
            const { how, res } = await probe(apiUrl(twin, username, password));
            if (how === "ok" && (await res.json().catch(() => null))?.user_info) {
                step("steps", "ok", "The provider's https port works too, so no setting is needed.");
                found.base = twin;
                facts.needsInsecure = false;
            } else step("steps", "info", "The provider names an https port, but it doesn't work from a web page.");
        } else step("steps", "info", "The provider has no https address.");
    }
    session = { base: found.base, username, password };
    await loadLists();
}

async function list(action, params) {
    const { how, res } = await probe(apiUrl(session.base, session.username, session.password, action, params));
    if (how !== "ok") return [];
    const json = await res.json().catch(() => null);
    return Array.isArray(json) ? json : [];
}

async function loadLists() {
    const fill = (select, items, label) => {
        select.replaceChildren(...items.map((it) => new Option(it.name || label, it.stream_id)));
        select.disabled = items.length === 0;
    };
    const [liveCats, vodCats] = await Promise.all([list("get_live_categories"), list("get_vod_categories")]);
    const live = liveCats.length ? (await list("get_live_streams", { category_id: liveCats[0].category_id })).slice(0, 200) : [];
    const films = vodCats.length ? (await list("get_vod_streams", { category_id: vodCats[0].category_id })).slice(0, 200) : [];
    step(
        "steps",
        live.length ? "ok" : "warn",
        live.length
            ? `Read the channel list (first group: ${live.length} ${live.length === 1 ? "channel" : "channels"}).`
            : "Couldn't read any live channels.",
    );
    fill($("live"), live, "Channel");
    session.films = Object.fromEntries(films.map((f) => [String(f.stream_id), f]));
    fill($("film"), films, "Film");
    $("play-film").disabled = films.length === 0;
    $("play-hls").disabled = $("play-ts").disabled = live.length === 0;
    $("play-card").hidden = false;
}

async function checkLink(text) {
    secrets = [];
    const server = parseServer(text);
    if (!server) return step("steps", "bad", "That doesn't look like a link.");
    if (server.username) return checkXtream(server, server.username, server.password);
    const url = server.playlistLink ?? text;
    step("steps", "info", `Your link uses ${server.protocol}. This page is ${location.protocol.replace(":", "")}.`);
    const { how, res } = await probe(url);
    if (how !== "ok") {
        facts.api = how;
        return step("steps", "bad", why(how));
    }
    facts.api = "ok";
    facts.needsInsecure = isMixed(url, location.protocol);
    const entries = parseM3u(await res.text(), 300);
    if (!entries.length) return step("steps", "bad", "The link answered, but it isn't an M3U playlist.");
    step("steps", "ok", `Read the playlist and allowed to by the provider (first ${entries.length} entries).`);
    const live = entries.filter((e) => streamKind(e.url) !== "file");
    const films = entries.filter((e) => streamKind(e.url) === "file");
    session = { entries };
    $("live").replaceChildren(...live.map((e) => new Option(e.name, e.url)));
    $("film").replaceChildren(...films.map((e) => new Option(e.name, e.url)));
    $("play-hls").disabled = $("play-ts").disabled = live.length === 0;
    $("play-film").disabled = films.length === 0;
    $("play-card").hidden = false;
}

// ---------- playing: one stream at a time ----------

function stop() {
    stopCurrent();
    stopCurrent = () => {};
    for (const video of [$("video"), $("anyway-video")]) {
        video.onerror = null;
        video.pause();
        video.removeAttribute("src");
        video.load();
    }
}

function liveLink(kind) {
    const value = $("live").value;
    if (session.entries) return value;
    return liveUrl(session.base, session.username, session.password, value, kind === "hls" ? "m3u8" : "ts");
}

function watchFirstFrame(started, label) {
    const video = $("video");
    const done = () => {
        video.removeEventListener("playing", done);
        const s = ((performance.now() - started) / 1000).toFixed(1);
        step("play-steps", "ok", `${label}: first picture after ${s} s.`);
        if (label.startsWith("Live")) facts.live = facts.live === "codec" ? "codec" : "ok";
        else facts.film = facts.film ?? "ok";
        showAnswer();
    };
    video.addEventListener("playing", done);
}

function noteCodecs(label, video, audio) {
    const v = video ? codecName(video) : null;
    const a = audio ? codecName(audio) : null;
    if (v) step("play-steps", "info", `${label} video: ${v}.`);
    if (a) {
        const ok = window.MediaSource?.isTypeSupported?.(`audio/mp4; codecs="${audio}"`) ?? true;
        step(
            "play-steps",
            ok ? "info" : "warn",
            ok ? `${label} sound: ${a}.` : `${label} sound is ${a}, which ${browser} can't play, so it will be silent.`,
        );
        if (!ok && label.startsWith("Live")) facts.live = "codec";
    }
}

async function failed(label, url, kind) {
    if (kind === "codec") {
        step("play-steps", "bad", `${label}: ${browser} can't play it (a format it doesn't decode, or the file isn't there).`);
        if (label.startsWith("Live")) facts.live = "codec";
        else facts.film = "codec";
    } else {
        const { how } = await probeStream(url);
        step("play-steps", "bad", `${label}: ${why(how) ?? "it stopped with an error."}`);
        if (label.startsWith("Live")) facts.live = how === "ok" ? "unreachable" : how;
        else facts.film = how;
    }
    showAnswer();
}

/** After a failed stream: one short opaque request (closed at once) to say why. Never while something plays. */
async function probeStream(url) {
    const mixed = isMixed(url, location.protocol);
    if (mixed && !facts.insecureAllowed) return { how: "insecure" };
    const ctrl = new AbortController();
    try {
        const res = await fetch(url, { signal: ctrl.signal, cache: "no-store", credentials: "omit" });
        ctrl.abort();
        return { how: res.ok ? "ok" : "unreachable" };
    } catch {
        try {
            await fetch(url, { mode: "no-cors", signal: ctrl.signal, cache: "no-store", credentials: "omit" });
            ctrl.abort();
            return { how: "cors" };
        } catch {
            return { how: "unreachable" };
        }
    }
}

function playHls() {
    stop();
    const url = liveLink("hls");
    const video = $("video");
    const started = performance.now();
    const label = "Live (.m3u8)";
    step("play-steps", "info", `${label}: ${$("live").selectedOptions[0]?.text ?? "channel"}…`);
    watchFirstFrame(started, label);
    const Hls = window.Hls;
    if (Hls?.isSupported()) {
        const hls = new Hls({
            enableWorker: true,
            startFragPrefetch: false,
            maxBufferLength: 10,
            backBufferLength: 0,
            lowLatencyMode: false,
        });
        hls.once(Hls.Events.BUFFER_CODECS, (_e, d) => noteCodecs(label, d.video?.codec, d.audio?.codec));
        hls.on(Hls.Events.ERROR, (_e, d) => {
            if (!d.fatal) return;
            const codec = /codec|bufferAdd|incompatible/i.test(d.details ?? "");
            hls.destroy();
            stopCurrent = () => {};
            failed(label, url, codec ? "codec" : "network");
        });
        hls.loadSource(url);
        hls.attachMedia(video);
        stopCurrent = () => hls.destroy();
    } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
        video.src = url;
        video.onerror = () => failed(label, url, video.error?.code === 4 && !isMixed(url, location.protocol) ? "codec" : "network");
        stopCurrent = () => (video.onerror = null);
    } else return step("play-steps", "bad", `${browser} can't play HLS at all.`);
    video.play().catch(() => {});
}

function playTs() {
    stop();
    const url = liveLink("ts");
    const mpegts = window.mpegts;
    const label = "Live (.ts)";
    if (!mpegts?.isSupported()) return step("play-steps", "bad", `${browser} can't play .ts live streams.`);
    step("play-steps", "info", `${label}: ${$("live").selectedOptions[0]?.text ?? "channel"}…`);
    const video = $("video");
    const started = performance.now();
    watchFirstFrame(started, label);
    const player = mpegts.createPlayer(
        { type: "mpegts", isLive: true, url },
        { enableWorker: false, lazyLoad: false, liveBufferLatencyChasing: true },
    );
    player.on(mpegts.Events.MEDIA_INFO, (info) => noteCodecs(label, info.videoCodec, info.audioCodec));
    player.on(mpegts.Events.ERROR, (type) => {
        player.destroy();
        stopCurrent = () => {};
        failed(label, url, type === mpegts.ErrorTypes.MEDIA_ERROR ? "codec" : "network");
    });
    player.attachMediaElement(video);
    player.load();
    stopCurrent = () => player.destroy();
    Promise.resolve(player.play()).catch(() => {});
}

async function playFilm() {
    stop();
    const label = "Film";
    const video = $("video");
    let url = $("film").value;
    step("play-steps", "info", `${label}: ${$("film").selectedOptions[0]?.text ?? "film"}…`);
    if (!session.entries) {
        const film = session.films[url];
        const ext = film?.container_extension || "mp4";
        url = movieUrl(session.base, session.username, session.password, film.stream_id, ext);
        const { how, res } = await probe(
            apiUrl(session.base, session.username, session.password, "get_vod_info", { vod_id: film.stream_id }),
        );
        const details = how === "ok" ? await res.json().catch(() => null) : null;
        const vName = details?.info?.video?.codec_name;
        const aName = details?.info?.audio?.codec_name;
        const container = containerMime(ext);
        step("play-steps", "info", `File: .${ext}${vName ? `, video ${vName}` : ""}${aName ? `, sound ${aName}` : ""}.`);
        const canBox = container ? video.canPlayType(container) : "";
        const vMime = codecMime(vName);
        const aMime = codecMime(aName);
        const canV = vMime ? window.MediaSource?.isTypeSupported?.(`video/mp4; codecs="${vMime}"`) : null;
        const canA = aMime ? window.MediaSource?.isTypeSupported?.(`audio/mp4; codecs="${aMime}"`) : null;
        if (!canBox) step("play-steps", "warn", `${browser} doesn't officially play .${ext} files; trying anyway.`);
        if (canV === false) step("play-steps", "warn", `${browser} can't decode ${vName} video.`);
        if (canA === false) {
            step("play-steps", "warn", `${browser} can't play ${aName} sound, so this film would be silent here.`);
            facts.film = "sound";
        }
    }
    const started = performance.now();
    watchFirstFrame(started, label);
    video.onerror = () => {
        video.onerror = null;
        failed(label, url, video.error?.code === 4 && (!isMixed(url, location.protocol) || facts.insecureAllowed) ? "codec" : "network");
    };
    stopCurrent = () => (video.onerror = null);
    video.src = url;
    video.play().catch(() => {});
}

// ---------- try playing anyway ----------
// Melvin, 2026-11-20: "a playlist might fail a check, can we try a webstream anyways?" A <video> needs no CORS, so the
// browser's own player can sometimes play a stream the check couldn't read. Still one stream at a time, straight from
// the provider.

const TRY_MS = 15_000;

/** After a failed check whose lists couldn't be read: offer the card, and a button in the answer that goes to it. */
function showAnyway(key, box) {
    const card = $("anyway-card");
    if (!canTryAnyway(key) || !$("play-card").hidden) {
        if (!facts.anyway) card.hidden = true;
        return;
    }
    const xtream = Boolean(login);
    $("anyway-label").textContent = xtream ? "Channel number or stream link" : "Stream link";
    $("anyway-input").inputMode = xtream ? "text" : "url";
    $("anyway-hint").textContent = xtream
        ? "A channel's number is the last part of its stream link, such as 1234 in …/live/name/password/1234.ts. Or paste a whole link."
        : "Paste the link of one channel from your playlist, such as http://example.com/live/1234.ts.";
    card.hidden = false;
    if (facts.anyway === "ok") return;
    const go = Object.assign(document.createElement("button"), { type: "button", textContent: "Try playing anyway" });
    go.addEventListener("click", () => {
        card.scrollIntoView({ block: "start", behavior: "smooth" });
        $("anyway-input").focus({ preventScroll: true });
    });
    const row = Object.assign(document.createElement("div"), { className: "row" });
    row.append(go);
    box.append(row);
}

/** One way of playing one link, in the card's video. "playing", or why it didn't: "error", "timeout" or "skip". */
function tryOne({ url, how }) {
    const video = $("anyway-video");
    return new Promise((resolve) => {
        let settled = false;
        let cleanup = () => {};
        const finish = (result) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            video.removeEventListener("playing", onPlaying);
            if (result !== "playing") {
                cleanup();
                cleanup = () => {};
            }
            resolve(result);
        };
        const onPlaying = () => finish("playing");
        const timer = setTimeout(() => finish("timeout"), TRY_MS);
        video.addEventListener("playing", onPlaying);
        if (how === "native") {
            video.onerror = () => finish("error");
            video.src = url;
            cleanup = () => {
                video.onerror = null;
                video.removeAttribute("src");
                video.load();
            };
        } else if (how === "hls") {
            const Hls = window.Hls;
            if (!Hls?.isSupported()) return finish("skip");
            const hls = new Hls({ enableWorker: true, startFragPrefetch: false, maxBufferLength: 10, backBufferLength: 0 });
            hls.on(Hls.Events.ERROR, (_e, d) => d.fatal && finish("error"));
            hls.loadSource(url);
            hls.attachMedia(video);
            cleanup = () => hls.destroy();
        } else {
            const mpegts = window.mpegts;
            if (!mpegts?.isSupported()) return finish("skip");
            const player = mpegts.createPlayer({ type: "mpegts", isLive: true, url }, { enableWorker: false, lazyLoad: false });
            player.on(mpegts.Events.ERROR, () => finish("error"));
            player.attachMediaElement(video);
            player.load();
            Promise.resolve(player.play()).catch(() => {});
            cleanup = () => player.destroy();
        }
        video.play().catch(() => {});
        stopCurrent = () => {
            finish("stopped");
            cleanup();
        };
    });
}

const HOW = { native: "the browser's own player", hls: "hls.js (.m3u8)", ts: "mpegts.js (.ts)" };

async function playAnyway(event) {
    event.preventDefault();
    stop();
    $("anyway-steps").replaceChildren();
    const raw = $("anyway-input").value.trim();
    if (raw.length >= 8 && !secrets.includes(raw) && /[?&](password|token|key)=/i.test(raw)) secrets.push(raw);
    const tries = anywayTries(raw, login);
    if (!tries.length)
        return step(
            "anyway-steps",
            "bad",
            login ? "Type a channel's number or paste a link that starts with http." : "Paste a link that starts with http.",
        );
    const button = $("anyway-play");
    button.disabled = true;
    try {
        if (isMixed(tries[0].url, location.protocol) && !facts.insecureAllowed)
            step("anyway-steps", "warn", `The stream is http and this page is https, so ${browser} may block it.`);
        let last = null;
        for (const t of tries) {
            stop();
            step("anyway-steps", "info", `Trying ${HOW[t.how]}…`);
            const started = performance.now();
            const result = await tryOne(t);
            if (result === "stopped") return step("anyway-steps", "info", "Stopped.");
            if (result === "playing") {
                const secs = ((performance.now() - started) / 1000).toFixed(1);
                step("anyway-steps", "ok", `It plays, with ${HOW[t.how]}: first picture after ${secs} s.`);
                facts.anyway = "ok";
                showAnswer();
                return;
            }
            step(
                "anyway-steps",
                result === "skip" ? "info" : "bad",
                result === "skip"
                    ? `${browser} can't use ${HOW[t.how]}.`
                    : result === "timeout"
                      ? `No picture after ${TRY_MS / 1000} s.`
                      : `${HOW[t.how][0].toUpperCase()}${HOW[t.how].slice(1)} couldn't play it.`,
            );
            last = t.url;
        }
        stop();
        facts.anyway = "failed";
        const { how } = await probeStream(last);
        step(
            "anyway-steps",
            "bad",
            how === "ok"
                ? `It didn't play. The stream answers, but ${browser} can't play its format. The Tuned app can.`
                : `It didn't play. ${why(how) ?? "The stream didn't answer."} The Tuned app plays it without these limits.`,
        );
        showAnswer();
    } finally {
        button.disabled = false;
    }
}

// ---------- the answer ----------

const ANSWERS = {
    works: ["Works in this browser", "Your provider allows web players and this browser plays its streams."],
    "list-only": ["The list works", "Your provider allows web players. Try a channel below to finish the check."],
    "works-after-setting": [
        "Works after one setting",
        "Your provider only has an http address. It works here because insecure content is allowed for this site; anyone else needs the same one-time setting.",
    ],
    "allow-insecure": [
        "Allow insecure content, then check again",
        "Your provider only has an http address, and the browser blocked it. Allow it for this site and run the check again:",
    ],
    "not-safari": [
        "Works in Chrome or Edge, not in Safari",
        "Your provider only has an http address, and Safari has no setting to allow that. Chrome, Edge or Firefox can, after one setting.",
    ],
    blocked: [
        "Your provider doesn't allow web players",
        "Use the Tuned app instead. A web page can't read this provider's lists or streams.",
    ],
    codec: [
        "This browser can't play these streams",
        "The provider works, but its video or sound format isn't one this browser decodes. Try Edge or Safari, or the Tuned app.",
    ],
    portal: ["MAC portals need the Tuned app", "A browser can't sign in to a MAC portal, because it can't pose as a set-top box."],
    login: ["Wrong username or password", "The provider answered but didn't accept the login."],
    unreachable: ["No answer from the provider", "Check the address. If the Tuned app works with it, the provider may block browsers."],
    "plays-anyway": [
        "A channel plays, but the lists don't",
        "The browser's own player played it, though this browser can't read your channel lists. A web player needs those lists, so watch in the Tuned app for now.",
    ],
};

function insecureHowTo() {
    const ol = document.createElement("ol");
    const lines =
        browser === "Firefox"
            ? [
                  "Click the padlock left of the address.",
                  "Turn off protection for this site (Connection secure › Disable protection for now).",
                  "Reload and check again.",
              ]
            : [
                  "Click the icon left of the address (the tune or padlock icon).",
                  "Open Site settings.",
                  "Set Insecure content to Allow.",
                  "Come back, reload, and check again.",
              ];
    ol.replaceChildren(...lines.map((t) => Object.assign(document.createElement("li"), { textContent: t })));
    return ol;
}

function showAnswer() {
    const key = verdict({ ...facts, safari });
    const [title, text] = ANSWERS[key];
    const box = $("answer");
    const h = Object.assign(document.createElement("h3"), { textContent: title });
    const p = Object.assign(document.createElement("p"), { textContent: text });
    box.replaceChildren(h, p);
    if ((key === "works-after-setting" && !facts.insecureAllowed) || key === "allow-insecure") box.append(insecureHowTo());
    box.hidden = false;
    showAnyway(key, box);
    facts.answer = key;
    updateSummary();
}

function updateSummary() {
    const head = [
        `Tuned provider check, ${new Date().toISOString().slice(0, 16)}Z`,
        `Browser: ${browser} (${navigator.userAgentData?.platform ?? navigator.platform})`,
        `Insecure content allowed: ${facts.insecureAllowed ? "yes" : "no"}`,
        `Answer: ${facts.answer ?? "not yet"}`,
        "",
    ];
    const host = session?.base ? new URL(session.base).hostname : null;
    $("summary").value = head.concat(report.map((l) => (host ? l.split(host).join("provider") : l))).join("\n");
    $("copy-card").hidden = false;
}

// ---------- wiring ----------

async function run(fn) {
    stop();
    facts = { insecureAllowed: await insecureAllowed() };
    report = [];
    session = null;
    $("steps").replaceChildren();
    $("play-steps").replaceChildren();
    $("play-card").hidden = true;
    $("anyway-card").hidden = true;
    $("anyway-steps").replaceChildren();
    $("anyway-input").value = "";
    login = null;
    $("steps-card").hidden = false;
    for (const b of document.querySelectorAll("button.primary")) b.disabled = true;
    try {
        await fn();
    } catch (e) {
        step("steps", "bad", `Something went wrong: ${e?.message ?? e}`);
    } finally {
        for (const b of document.querySelectorAll("button.primary")) b.disabled = false;
        showAnswer();
    }
}

let autoLink = "";

/** Switching to Playlist link builds the M3U link from a filled-in Xtream login, unless the person typed their own. */
function fillLinkFromLogin() {
    const server = parseServer($("server").value);
    const username = $("username").value.trim();
    const password = $("password").value;
    if (!server || !username || !password) return;
    const current = $("playlist").value.trim();
    if (current && current !== autoLink) return;
    autoLink = playlistUrl(server.base, username, password);
    $("playlist").value = autoLink;
}

/** And back: a pasted get.php link fills the empty Xtream fields. */
function fillLoginFromLink() {
    const link = parseServer($("playlist").value);
    if (!link?.username) return;
    if (!$("server").value.trim()) $("server").value = link.base;
    if (!$("username").value.trim()) $("username").value = link.username;
    if (!$("password").value) $("password").value = link.password;
}

function tabs() {
    const pairs = [
        ["tab-xtream", "xtream"],
        ["tab-link", "link"],
    ];
    for (const [tab, panel] of pairs)
        $(tab).addEventListener("click", () => {
            if (panel === "link") fillLinkFromLogin();
            else fillLoginFromLink();
            for (const [t, p] of pairs) {
                $(t).setAttribute("aria-selected", String(t === tab));
                $(p).hidden = p !== panel;
            }
        });
}

async function start() {
    if (!(await enabled())) {
        $("off").hidden = false;
        return;
    }
    tabs();
    const autoCheck = await loadSaved();
    $("form-card").hidden = !$("saved-card").hidden;
    $("type-instead").addEventListener("click", () => {
        const open = $("form-card").hidden;
        $("form-card").hidden = !open;
        $("type-instead").setAttribute("aria-expanded", String(open));
        if (open) $("server").focus();
    });
    $("saved").addEventListener("submit", (e) => {
        e.preventDefault();
        const id = $("saved").elements.saved?.value ?? document.querySelector('input[name="saved"]:checked')?.value;
        if (id) checkSaved(id);
    });
    $("anyway").addEventListener("submit", playAnyway);
    $("anyway-stop").addEventListener("click", stop);
    $("xtream").addEventListener("submit", (e) => {
        e.preventDefault();
        const raw = $("server").value;
        run(async () => {
            if (looksLikePortal(raw)) {
                facts.portal = true;
                return step("steps", "bad", "This looks like a MAC portal.");
            }
            const server = parseServer(raw);
            if (!server) return step("steps", "bad", "That doesn't look like a server address.");
            await checkXtream(server, $("username").value.trim(), $("password").value);
        });
    });
    $("link").addEventListener("submit", (e) => {
        e.preventDefault();
        const raw = $("playlist").value.trim();
        run(async () => {
            if (looksLikePortal(raw)) {
                facts.portal = true;
                return step("steps", "bad", "This looks like a MAC portal.");
            }
            await checkLink(raw);
        });
    });
    $("play-hls").addEventListener("click", playHls);
    $("play-ts").addEventListener("click", playTs);
    $("play-film").addEventListener("click", playFilm);
    $("stop").addEventListener("click", stop);
    $("sound").addEventListener("click", () => {
        $("video").muted = !$("video").muted;
        $("sound").textContent = $("video").muted ? "Turn sound on" : "Turn sound off";
    });
    $("copy").addEventListener("click", () => navigator.clipboard?.writeText($("summary").value));
    addEventListener("pagehide", stop);
    // Opened from a playlist's Check button on the account's Playlists page.
    if (autoCheck) checkSaved(new URLSearchParams(location.search).get("playlist"));
}

enhanceSelect($("live"));
enhanceSelect($("film"));
start();
