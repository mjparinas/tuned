// The top picture of each blog post that has one (docs/decisions/website.md › The blog): the app's own screens in the
// email pictures' frames, drawn by `node devices/email-images.mjs <screens> [id] --blog` into
// devices/public/blog/img/posts/<id>.jpg (1200 × 760). Made-up channels only, never a real provider, channel or brand.

const step = (cls, big, small, compact = false) =>
    compact
        ? `<div class="step ${cls}" style="min-width:0;padding:16px 22px;gap:14px"><span class="dot"></span><div><b style="font-size:24px;white-space:nowrap">${big}</b><small style="font-size:18px">${small}</small></div></div>`
        : `<div class="step ${cls}"><span class="dot"></span><div><b>${big}</b><small>${small}</small></div></div>`;
const arrow = `<span class="arrow">→</span>`;

export function blogImages({ phone, tv, browser, player, event, stack, mark }) {
    return {
        "android-phone": {
            chip: "IPTV player for Android phones",
            body: stack(phone("browse-live-tv.png", -4), phone("browse-guide.png", 0), phone("browse-movie.png", 4)),
        },
        "set-up-tv": {
            chip: "Set up IPTV on your TV",
            body: stack(phone("playlist-send-to-tv.png", -3), tv("playlist-welcome-tv.png", 640)),
        },
        "switch-player": {
            chip: "Switch players in 5 minutes",
            body: stack(phone("playlist-welcome.png", -3), phone("playlist-add-xtream.png", 0), phone("browse-live-tv-filters.png", 3)),
        },
        "streaming-box": {
            chip: "The best box for IPTV",
            body: stack(tv("browse-live-tv-tv.png", 820)),
        },
        "live-sports": {
            chip: "Never miss a game",
            premium: "both",
            body: `<div class="events"><h3>Today</h3>
            ${event("7:00 PM", "Harbour Hawks vs Lakeside Lions", "Rink Sports 1 · 4K", "Tune")}
            ${event("8:30 PM", "Northern Cup: Final", "Rink Sports 2 · FHD", "Tune")}
            ${event("10:00 PM", "Main event: Reyes vs Okafor", "Fight Night PPV 1", "Tune")}
            <h3>Tomorrow</h3>${event("1:00 PM", "Prairie Rovers vs Coast City", "Rink Sports 2 · FHD", "🔔")}</div>`,
        },
        "pause-rewind": { chip: "Pause, rewind and record", premium: true, body: player("player-controls.png") },
        "web-guide": { chip: "Your guide on a computer", premium: true, body: browser("web-guide.png") },
        "player-not-provider": {
            chip: "A player, not a provider",
            body: `<div class="flow">${step("wait", "Your provider", "sells the channels and the login")}${arrow}${step("on", "Tuned", "plays what you give it")}</div>
            <div class="hub"><div class="logo" style="width:150px;height:150px">${mark}</div></div>
            <p class="big">No channels included. Ever.</p>`,
        },
        "smarters-alternatives": {
            chip: "IPTV Smarters alternatives",
            body: stack(tv("browse-guide-grid-tv.png", 640), phone("browse-live-tv.png", 3)),
        },
        "free-no-ads": {
            chip: "Free, with no ads",
            body: stack(phone("browse-live-tv.png", -4), phone("browse-guide.png", 0), phone("browse-movie.png", 4)),
        },
        "playlist-types": {
            chip: "M3U, Xtream or MAC portal?",
            body: stack(phone("playlist-add-xtream.png", -4), phone("playlist-add-link.png", 0), phone("playlist-add-portal.png", 4)),
        },
        "iptv-not-working": {
            chip: "IPTV not working? 10 fixes",
            body: stack(phone("browse-first-download.png", -3), phone("settings-user-agents.png", 3)),
        },
        "samsung-lg": { chip: "Your playlist on a Samsung or LG TV", body: player("player-casting.png") },
        "parental-controls": {
            chip: "Parental controls",
            premium: true,
            body: stack(phone("browse-profiles-named.png", -3), tv("browse-profiles-tv.png", 600)),
        },
        connections: {
            chip: "Connections explained",
            body: `<div class="flow">${step("on", "Watching", "uses the connection")}${arrow}${step("wait", "Downloads and scans", "wait their turn")}</div>
            <p class="big">Your plan counts streams, not devices.</p>`,
        },
        "trakt-simkl": {
            chip: "Trakt and Simkl",
            premium: true,
            body: stack(phone("settings-simkl-import.png", -3), phone("browse-shows-recently-added.png", 3)),
        },
        offline: {
            chip: "Download for offline",
            premium: "both",
            body: stack(phone("browse-show.png", -4), phone("browse-downloads.png", 0), phone("browse-movie.png", 4)),
        },
        "fire-tv": {
            chip: "Fire TV",
            body: `${stack(tv("playlist-welcome-tv.png", 640))}
            <div class="flow" style="gap:18px">${[
                step("off", "1 · Downloader", "from the Appstore", true),
                step("off", "2 · tunedplayer.com/download", "type it in Downloader", true),
                step("on", "3 · Install", "then sign in", true),
            ].join(arrow)}</div>`,
        },
    };
}
