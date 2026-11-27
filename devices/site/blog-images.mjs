// The top picture of each blog post that has one (docs/decisions/website.md › The blog): the app's own screens in the
// email pictures' frames, drawn by `node devices/email-images.mjs <screens> [id] --blog` into
// devices/public/blog/img/posts/<id>.jpg (1200 × 760). Made-up channels only, never a real provider, channel or brand.

const step = (cls, big, small, compact = false) =>
    compact
        ? `<div class="step ${cls}" style="min-width:0;padding:16px 22px;gap:14px"><span class="dot"></span><div><b style="font-size:24px;white-space:nowrap">${big}</b><small style="font-size:18px">${small}</small></div></div>`
        : `<div class="step ${cls}"><span class="dot"></span><div><b>${big}</b><small>${small}</small></div></div>`;
const arrow = `<span class="arrow">→</span>`;

export function blogImages({ phone, tv, browser, player, event, stack, tiles, mark }) {
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
        "data-usage": {
            chip: "How much data IPTV uses",
            body: `<div class="flow" style="gap:18px">${[
                step("off", "SD", "about 1 GB an hour", true),
                step("wait", "HD", "about 2 GB", true),
                step("wait", "Full HD", "about 3 GB", true),
                step("on", "4K", "7 to 11 GB", true),
            ].join("")}</div>
            <p class="big">Per stream, per hour. Tuned only ever plays one.</p>`,
        },
        "how-iptv-works": {
            chip: "How IPTV works",
            body: `<div class="flow" style="gap:18px">${[
                step("off", "Your provider", "channels, login, guide", true),
                step("wait", "Your device", "keeps the list and guide", true),
                step("on", "Your screen", "one stream plays", true),
            ].join(arrow)}</div>
            <div class="hub"><div class="logo" style="width:130px;height:130px">${mark}</div></div>`,
        },
        "big-playlist": {
            chip: "Tame a huge playlist",
            body: stack(
                phone("browse-live-tv-group-chosen.png", -4),
                phone("settings-countries.png", 0),
                phone("browse-live-tv-pinned.png", 4),
            ),
        },
        "picture-sound": {
            chip: "Best picture and sound",
            body: stack(phone("settings-page-playback.png", -3), tv("settings-tv-calibrate.png", 620)),
        },
        "media-server-iptv": {
            chip: "Plex, Jellyfin or Emby plus IPTV",
            body: stack(phone("playlist-add-jellyfin.png", -3), phone("browse-shows.png", 3)),
        },
        "app-safety": {
            chip: "Is that IPTV app safe?",
            body: `<div class="flow">${step("on", "tunedplayer.com/download", "the real Tuned")}${step("off", "Modded copies", "never type a login into one")}</div>
            <div class="hub"><div class="logo" style="width:130px;height:130px">${mark}</div></div>`,
        },
        "new-device": {
            chip: "Move to a new phone or TV",
            body: stack(phone("playlist-send-to-tv.png", -3), tv("playlist-welcome-tv.png", 620)),
        },
        "epg-deep-dive": {
            chip: "How TV guides work",
            body: stack(tv("browse-guide-grid-tv.png", 640), phone("browse-search-guide.png", 3)),
        },
        accessible: {
            chip: "Accessible IPTV",
            body: stack(phone("settings-captions.png", -3), phone("settings-page-appearance.png", 3)),
        },
        // Every post has a top picture (Melvin, 2026-11-27: "ensure that every post has a hero image"). The guides,
        // comparisons and picks below got theirs that day; `whats-new` is every release post's (release.yml writes
        // those), and `blog` is the stand-in for any post that still has none (`heroOf` in lib/site-build.mjs).
        "whats-new": {
            chip: "What's new in Tuned",
            body: stack(
                phone("browse-live-tv.png", -3),
                `<div class="spokes"><div class="pill">New</div><div class="pill soft">Faster</div><div class="pill soft">Fixed</div></div>`,
                tv("browse-guide-grid-tv.png", 520),
            ),
        },
        blog: {
            chip: "The Tuned blog",
            note: true,
            body: stack(tv("browse-live-tv-tv.png", 640), phone("browse-guide.png", 3)),
        },
        "add-xtream": {
            chip: "Add an Xtream Codes playlist",
            body: stack(phone("playlist-welcome.png", -4), phone("playlist-add-xtream.png", 0), phone("browse-live-tv.png", 4)),
        },
        "add-m3u": {
            chip: "Add an M3U link or file",
            body: stack(phone("playlist-welcome.png", -4), phone("playlist-add-link.png", 0), phone("browse-guide.png", 4)),
        },
        "add-mac-portal": {
            chip: "Add a MAC portal",
            body: stack(phone("playlist-add-portal.png", -3), tv("browse-live-tv-tv.png", 600)),
        },
        "add-media-server": {
            chip: "Add Plex, Jellyfin or Emby",
            body: stack(phone("playlist-add-jellyfin.png", -4), phone("browse-movies.png", 0), phone("browse-movie.png", 4)),
        },
        "android-tv": {
            chip: "Tuned on Android TV",
            body: stack(tv("browse-live-tv-tv.png", 820)),
        },
        "blank-tv-guide": {
            chip: "Fix an empty TV guide",
            body: stack(phone("settings-page-guide.png", -3), tv("browse-guide-grid-tv.png", 620)),
        },
        buffering: {
            chip: "Fix buffering",
            body: stack(
                phone("settings-page-reliability.png", -4),
                phone("settings-page-playback.png", 0),
                phone("settings-user-agents.png", 4),
            ),
        },
        casting: { chip: "Cast to a Chromecast or smart TV", body: player("player-casting.png") },
        "catch-up": {
            chip: "Catch-up TV",
            body: stack(phone("browse-guide.png", -3), tv("browse-guide-grid-tv.png", 620)),
        },
        glossary: {
            chip: "IPTV glossary",
            body: `<div class="hub"><div class="logo" style="width:150px;height:150px">${mark}</div><div class="spokes grid">
            <div class="pill">EPG</div><div class="pill">M3U</div><div class="pill">Xtream</div>
            <div class="pill soft">Catch-up</div><div class="pill soft">MAC portal</div><div class="pill soft">User agent</div></div></div>`,
        },
        "google-tv": {
            chip: "Tuned on Google TV",
            body: stack(tv("browse-show-tv.png", 640), phone("browse-movie.png", 3)),
        },
        "nvidia-shield": {
            chip: "Best settings for Nvidia Shield",
            body: stack(tv("settings-tv.png", 620), phone("settings-page-playback.png", 3)),
        },
        "premium-guide": {
            chip: "What Premium gets you",
            premium: true,
            body: tiles(
                [
                    ["⏪", "Pause, rewind and record live TV"],
                    ["🔔", "Follow teams, get alerts"],
                    ["👪", "Six profiles, kids and PINs"],
                    ["📚", "Many playlists and your own lists"],
                    ["📱", "Three devices on one account"],
                    ["🎨", "Themes, Trakt, Simkl, more downloads"],
                ],
                "six",
            ),
        },
        "privacy-guide": {
            chip: "What Tuned stores",
            body: stack(phone("settings-logs.png", -3), phone("settings-sign-out.png", 3)),
        },
        profiles: {
            chip: "Profiles, kids and PINs",
            premium: true,
            body: stack(phone("browse-profiles-named.png", -3), tv("browse-profiles-home-tv.png", 620)),
        },
        "provider-errors": {
            chip: "Provider errors explained",
            body: stack(phone("browse-first-download.png", -3), phone("settings-logs.png", 3)),
        },
        "recordings-downloads": {
            chip: "Record and download",
            premium: "both",
            body: stack(phone("browse-show.png", -4), phone("browse-downloads.png", 0), phone("browse-downloads-home.png", 4)),
        },
        "sign-in-tv": {
            chip: "Sign in on a TV with your phone",
            body: stack(phone("playlist-send-to-tv.png", -3), tv("playlist-tv-sign-in.png", 620)),
        },
        subtitles: {
            chip: "Subtitles and captions",
            body: stack(phone("settings-captions.png", -3), tv("settings-captions-tv.png", 620)),
        },
        tablets: { chip: "Tablets, foldables and Chromebooks", body: stack(tv("browse-live-tv-tablet.png", 900)) },
        "tv-guide": {
            chip: "Set up the TV guide",
            body: stack(phone("browse-guide.png", -3), tv("browse-guide-grid-tv.png", 620)),
        },
        "user-agent": {
            chip: "Change the user agent",
            body: stack(phone("settings-user-agents.png", -3), phone("settings-page-reliability.png", 3)),
        },
        "best-android-tv": {
            chip: "Best IPTV player for Android TV",
            note: true,
            body: stack(tv("browse-guide-grid-tv.png", 640), phone("browse-live-tv.png", 3)),
        },
        "tivimate-alternatives": {
            chip: "TiviMate alternatives",
            note: true,
            body: stack(tv("browse-live-tv-tv.png", 640), phone("browse-guide.png", 3)),
        },
        ...versus({ phone, tv, player, stack }),
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

// The comparisons' pictures: Tuned's own screens under "Tuned vs <app>", never the other app's screens or logo.
const VERSUS = [
    ["duplecast", "Duplecast", "live-guide"],
    ["flix-iptv", "Flix IPTV", "movies"],
    ["gse-smart-iptv", "GSE Smart IPTV", "phones"],
    ["ibo-player", "IBO Player", "tv-guide"],
    ["implayer", "iMPlayer", "movies-tv"],
    ["iptv-pro", "IPTV Pro", "phones"],
    ["iptv-smarters-pro", "IPTV Smarters Pro", "live-guide"],
    ["kodi", "Kodi", "shows-tv"],
    ["ott-navigator", "OTT Navigator", "tv-guide"],
    ["ottplayer", "OttPlayer", "settings"],
    ["perfect-player", "Perfect Player", "tv-live"],
    ["purple-player", "Purple Playlist Player", "phones"],
    ["set-iptv", "SET IPTV", "tv-live"],
    ["smart-iptv", "Smart IPTV", "casting"],
    ["sparkle-tv", "Sparkle TV", "tv-guide"],
    ["televizo", "Televizo", "live-guide"],
    ["tivimate", "TiviMate", "tv-guide"],
    ["uhf", "UHF", "movies"],
    ["vlc", "VLC", "player"],
    ["xciptv", "XCIPTV", "shows-tv"],
];

function versus({ phone, tv, player, stack }) {
    const layouts = {
        "live-guide": () => stack(tv("browse-live-tv-tv.png", 640), phone("browse-guide.png", 3)),
        "tv-guide": () => stack(tv("browse-guide-grid-tv.png", 640), phone("browse-live-tv.png", 3)),
        "tv-live": () => stack(tv("browse-live-tv-tv.png", 820)),
        movies: () => stack(phone("browse-movies.png", -4), phone("browse-movie.png", 0), phone("browse-shows.png", 4)),
        "movies-tv": () => stack(tv("browse-search-tv.png", 640), phone("browse-movie.png", 3)),
        "shows-tv": () => stack(tv("browse-show-tv.png", 640), phone("browse-shows.png", 3)),
        phones: () => stack(phone("browse-live-tv.png", -4), phone("browse-guide.png", 0), phone("browse-movie.png", 4)),
        settings: () => stack(phone("settings.png", -4), phone("settings-page-playback.png", 0), phone("browse-live-tv.png", 4)),
        casting: () => player("player-casting.png"),
        player: () => player("player-controls.png"),
    };
    return Object.fromEntries(
        VERSUS.map(([id, name, layout]) => [`vs-${id}`, { chip: `Tuned vs ${name}`, note: true, body: layouts[layout]() }]),
    );
}
