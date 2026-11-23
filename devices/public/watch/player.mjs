// One stream at a time for the web player (docs/decisions/web.md): destroy the old loader before the next opens,
// nothing prefetches. Tries the browser's own player first (a <video> needs no CORS), then hls.js or mpegts.js.
import { anywayTries, isMixed } from "../check/verdict.mjs";

const TRY_MS = 12_000;
const HOW = { native: "the browser's own player", hls: "hls.js", ts: "mpegts.js" };

/** The https twin of an http stream link, so an https page can try it without insecure content. Null when already https. */
export function httpsStream(url) {
    try {
        const u = new URL(url);
        if (u.protocol !== "http:") return null;
        u.protocol = "https:";
        if (u.port === "80") u.port = "";
        return u.href;
    } catch {
        return null;
    }
}

/**
 * What to try for a channel's stream link, https first when the page is https and the link is http. One entry per
 * (url, how), in order.
 */
export function playTries(url, pageProtocol = "https:") {
    const urls = [];
    if (isMixed(url, pageProtocol)) {
        const twin = httpsStream(url);
        if (twin) urls.push(twin);
    }
    urls.push(url);
    const out = [];
    const seen = new Set();
    for (const u of urls) {
        for (const t of anywayTries(u)) {
            const key = `${t.how}|${t.url}`;
            if (seen.has(key)) continue;
            seen.add(key);
            out.push(t);
        }
    }
    return out;
}

/**
 * Plays `url` into `video`, one try at a time. Returns `{ stop, result }` where `result` resolves to
 * `{ ok: true, how }` or `{ ok: false, why, tried }`. Calling `stop` cancels.
 */
export function playStream(video, url, { pageProtocol = location.protocol } = {}) {
    let stopCurrent = () => {};
    let stopped = false;
    const stop = () => {
        stopped = true;
        stopCurrent();
        stopCurrent = () => {};
        video.onerror = null;
        video.removeAttribute("src");
        video.load();
    };

    const tryOne = ({ url: src, how }) =>
        new Promise((resolve) => {
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
                video.src = src;
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
                hls.loadSource(src);
                hls.attachMedia(video);
                cleanup = () => hls.destroy();
            } else {
                const mpegts = window.mpegts;
                if (!mpegts?.isSupported()) return finish("skip");
                const player = mpegts.createPlayer({ type: "mpegts", isLive: true, url: src }, { enableWorker: false, lazyLoad: false });
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

    const result = (async () => {
        const tries = playTries(url, pageProtocol);
        if (!tries.length) return { ok: false, why: "bad link", tried: [] };
        const tried = [];
        for (const t of tries) {
            if (stopped) return { ok: false, why: "stopped", tried };
            stopCurrent();
            const outcome = await tryOne(t);
            tried.push({ how: t.how, label: HOW[t.how], outcome });
            if (outcome === "stopped") return { ok: false, why: "stopped", tried };
            if (outcome === "playing") return { ok: true, how: t.how, label: HOW[t.how], tried };
        }
        stop();
        return { ok: false, why: "failed", tried };
    })();

    return { stop, result };
}

export { HOW, TRY_MS };
