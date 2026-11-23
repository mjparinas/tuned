// The web player at /watch (docs/decisions/web.md): Premium Live TV in the browser, one stream and one browser at a
// time, straight from the provider with no relay. Channels come from the copy the Tuned app keeps in the account, as
// Channels and guide does. Behind the web_player flag (preview deployments and localhost always).
import { drawNav, flagOn } from "/account/nav.js";
import { enhanceSelect } from "/select.js";
import { isPreviewHost } from "/check/verdict.mjs";
import { ago, applyFilters, fromAppCopy, NO_FILTERS, nowNext, platformOf, vlcLink } from "/account/channels/core.mjs";
import { playStream } from "/watch/player.mjs";

const $ = (id) => document.getElementById(id);
const HOUR = 3_600_000;
const PAGE = 80;
const platform = platformOf(navigator.userAgent, navigator.maxTouchPoints);

const state = {
    playlists: [],
    current: null,
    channels: [],
    guide: new Map(),
    items: [],
    shown: PAGE,
    query: "",
    loadId: 0,
    playing: null,
    browserId: null,
    leaseTimer: null,
};

let stopPlay = () => {};

function tell(id, text, tone = "ok") {
    const line = $(id);
    if (!line) return;
    line.textContent = text ?? "";
    line.dataset.tone = tone;
    if (id === "lease") line.hidden = !text;
}

async function getJson(url, init) {
    try {
        const res = await fetch(url, { cache: "no-store", credentials: "same-origin", ...init });
        const body = await res.json().catch(() => ({}));
        return { http: res.status, ...body };
    } catch {
        return { http: 0, status: "unavailable" };
    }
}

function browserId() {
    const key = "tuned-watch-browser";
    let id = sessionStorage.getItem(key);
    if (!/^[0-9a-f-]{36}$/i.test(id ?? "")) {
        id = crypto.randomUUID();
        sessionStorage.setItem(key, id);
    }
    state.browserId = id;
    return id;
}

async function enabled() {
    if (isPreviewHost(location.hostname)) return true;
    return flagOn("web_player");
}

// ---------- lease ----------

async function claimLease({ steal = true } = {}) {
    const answer = await getJson("/api/account/watch", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ browserId: browserId(), steal }),
    });
    if (answer.status === "ok") {
        tell("lease", answer.tookOver ? "This browser took over watching from another one." : "", "warn");
        return true;
    }
    if (answer.status === "taken") {
        tell("lease", "Another browser is watching on this account. Stop there, or play here to take over.", "warn");
        return false;
    }
    if (answer.status === "need premium") {
        showGate("need-premium");
        return false;
    }
    if (answer.status === "off") {
        showGate("off");
        return false;
    }
    if (answer.status === "signed out") {
        showGate("signed-out");
        return false;
    }
    tell("lease", "Couldn't start watching just now. Try again.", "error");
    return false;
}

function startHeartbeat() {
    clearInterval(state.leaseTimer);
    state.leaseTimer = setInterval(() => {
        getJson("/api/account/watch", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ browserId: browserId(), steal: false }),
        }).then((answer) => {
            if (answer.status === "taken") {
                stop();
                tell("lease", "Another browser took over watching. Playback stopped here.", "warn");
            }
        });
    }, 15_000);
}

window.addEventListener("pagehide", () => {
    clearInterval(state.leaseTimer);
    if (!state.browserId) return;
    const body = JSON.stringify({ browserId: state.browserId });
    fetch("/api/account/watch", { method: "DELETE", headers: { "Content-Type": "application/json" }, body, keepalive: true }).catch(
        () => {},
    );
});

// ---------- channels ----------

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

function onNow(channel) {
    if (!channel?.epgId) return "";
    const shows = state.guide.get(channel.epgId);
    if (!shows?.length) return "";
    const { now } = nowNext(shows, Date.now());
    return now?.title ?? "";
}

function drawList() {
    const nowOf = (c) => {
        if (!c.epgId) return null;
        return nowNext(state.guide.get(c.epgId), Date.now()).now;
    };
    state.items = applyFilters(state.channels, { ...NO_FILTERS, query: state.query }, nowOf);
    const slice = state.items.slice(0, state.shown);
    $("count").textContent = state.items.length
        ? `${Math.min(state.shown, state.items.length).toLocaleString("en-CA")} of ${state.items.length.toLocaleString("en-CA")} channels`
        : state.channels.length
          ? "No channels match."
          : "";
    $("list").replaceChildren(
        ...slice.map((c) => {
            const li = document.createElement("li");
            const btn = document.createElement("button");
            btn.type = "button";
            if (state.playing && state.playing.url === c.url) btn.setAttribute("aria-current", "true");
            const num = Object.assign(document.createElement("span"), { className: "num", textContent: c.number || "" });
            const meta = Object.assign(document.createElement("span"), { className: "meta" });
            meta.append(
                Object.assign(document.createElement("span"), { className: "name", textContent: c.name }),
                Object.assign(document.createElement("span"), { className: "on-now", textContent: onNow(c) }),
            );
            btn.append(num, meta);
            btn.onclick = () => play(c);
            li.append(btn);
            return li;
        }),
    );
}

function showChannels(channels, guide, sourceLine) {
    for (const c of channels) c.hasGuide = Boolean(c.epgId && guide.has(c.epgId));
    state.channels = channels;
    state.guide = guide;
    state.shown = PAGE;
    tell("source", sourceLine, "ok");
    drawList();
}

async function openPlaylist(p) {
    stop();
    state.current = p;
    state.loadId += 1;
    const id = state.loadId;
    const url = new URL(location.href);
    url.searchParams.set("playlist", p.id);
    history.replaceState(null, "", url);
    $("channels-link").href = `/account/channels?playlist=${encodeURIComponent(p.id)}`;
    if (p.kind === "stalker") {
        tell("source", "A MAC portal only works in the Tuned app.", "error");
        state.channels = [];
        drawList();
        return;
    }
    tell("source", "Getting your channels…", "ok");
    const [login, copy] = await Promise.all([
        p.kind === "file" ? Promise.resolve({ status: "app only" }) : getJson(`/api/account/check?id=${encodeURIComponent(p.id)}`),
        appCopy(p.id),
    ]);
    if (id !== state.loadId) return;
    const hasLogin = login.status === "ok";
    const fromApp = copy.body ? fromAppCopy(copy.body, hasLogin ? login : null) : null;
    if (fromApp?.channels.length) {
        const from = copy.by ? `your Tuned app on ${copy.by}` : "your Tuned app";
        const when = fromApp.savedAt ? `, ${ago(fromApp.savedAt)}` : "";
        const stale = fromApp.savedAt && Date.now() - fromApp.savedAt > 24 * HOUR ? " Open Tuned to bring it up to date." : "";
        return showChannels(fromApp.channels, fromApp.guide, `From ${from}${when}.${stale}`);
    }
    if (!hasLogin) {
        tell(
            "source",
            login.status === "too many"
                ? "Too many playlists opened this hour. Try again later."
                : "No channels backed up for this playlist yet. Open it in Tuned and tap Back up now, or check that you're signed in.",
            "error",
        );
        state.channels = [];
        drawList();
        return;
    }
    tell(
        "source",
        "No channels backed up yet, and this page only plays from your app's copy so it doesn't ask the provider for lists. Open the playlist in Tuned and tap Back up now.",
        "error",
    );
    state.channels = [];
    drawList();
}

// ---------- playback ----------

function stop() {
    stopPlay();
    stopPlay = () => {};
    state.playing = null;
    $("video-wrap")?.classList.remove("playing");
    $("empty-stage").hidden = false;
    $("playing-name").textContent = "";
    $("playing-show").textContent = "";
    tell("play-status", "");
    $("stop").hidden = true;
    $("vlc").hidden = true;
    drawList();
}

async function play(channel) {
    if (!(await claimLease({ steal: true }))) return;
    startHeartbeat();
    stopPlay();
    state.playing = channel;
    $("video-wrap").classList.add("playing");
    $("empty-stage").hidden = true;
    $("playing-name").textContent = channel.name;
    $("playing-show").textContent = onNow(channel);
    $("stop").hidden = false;
    $("vlc").hidden = false;
    tell("play-status", "Starting…", "ok");
    drawList();
    const video = $("video");
    const session = playStream(video, channel.url);
    stopPlay = session.stop;
    const result = await session.result;
    if (state.playing !== channel) return;
    if (result.ok) {
        tell("play-status", "", "ok");
        return;
    }
    if (result.why === "stopped") return;
    tell(
        "play-status",
        "This browser couldn't play that stream. Open it in VLC, or try Channels and guide. Some providers block web players.",
        "error",
    );
}

function openVlc() {
    const channel = state.playing;
    if (!channel) return;
    const link = vlcLink(channel.url, platform, channel.name);
    if (link) location.href = link;
    else navigator.clipboard?.writeText(channel.url).catch(() => {});
}

// ---------- start ----------

function showGate(which) {
    for (const id of ["off", "signed-out", "need-premium", "signed-in"]) $(id).hidden = id !== which;
}

async function start() {
    if (!(await enabled())) return showGate("off");

    const me = await getJson("/api/account/me");
    if (me.status === "signed out" || me.http === 401) return showGate("signed-out");
    // Same account chrome as Channels and Check: left menu on a desktop once signed in.
    document.querySelector("main").classList.add("signed-in");
    drawNav($("account-nav"), "watch");
    $("account-nav").hidden = false;
    if (!me.premium) return showGate("need-premium");

    const library = await getJson("/api/account/library");
    if (library.status === "signed out") return showGate("signed-out");
    const playlists = (library.playlists ?? []).filter((p) => p.kind === "xtream" || p.kind === "link" || p.kind === "file");
    state.playlists = playlists;
    if (!playlists.length) {
        showGate("signed-in");
        tell("source", "No playlists in this account yet. Add one in the app or on Playlists and settings.", "error");
        return;
    }

    showGate("signed-in");
    browserId();
    const wanted = new URLSearchParams(location.search).get("playlist");
    const first = playlists.find((p) => p.id === wanted) ?? playlists[0];
    $("playlist").replaceChildren(...playlists.map((p) => new Option(p.name || p.detail || p.kind, p.id)));
    $("playlist").value = first.id;
    enhanceSelect($("playlist"));
    $("playlist").onchange = () => {
        const p = state.playlists.find((x) => x.id === $("playlist").value);
        if (p) openPlaylist(p);
    };
    $("search").oninput = () => {
        state.query = $("search").value;
        state.shown = PAGE;
        drawList();
    };
    $("list").onscroll = () => {
        const el = $("list");
        if (el.scrollTop + el.clientHeight > el.scrollHeight - 120 && state.shown < state.items.length) {
            state.shown += PAGE;
            drawList();
        }
    };
    $("stop").onclick = () => stop();
    $("vlc").onclick = () => openVlc();
    // Soft check: another browser may already hold the lease; don't steal until the person picks a channel.
    const lease = await getJson(`/api/account/watch?browserId=${encodeURIComponent(browserId())}`);
    if (lease.status === "ok" && lease.holder && !lease.yours) {
        tell("lease", "Another browser is watching on this account. Playing a channel here will take over.", "warn");
    }
    await openPlaylist(first);
}

start().catch(() => tell("status", "Something went wrong loading Watch.", "error"));
