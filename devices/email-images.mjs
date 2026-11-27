// Draws the pictures in the daily emails (docs/decisions/emails.md): the app's own screens, in phone, TV, tablet
// and browser frames on Tuned's ink-and-violet background, plus a few drawn graphics (speed, one stream, Premium).
// Writes devices/public/email/<id>.jpg, 1200 × 760, shown 560 wide in the email.
//
//   node devices/email-images.mjs <screens> [only-id]
//
// <screens> is a folder with the PNGs from the Screenshots workflow (the `screenshots/staging` branch:
// `git archive origin/screenshots/staging | tar -x -C <screens>`), plus `web-playlists.png`, the website's Playlists and
// settings page on a desktop. Uses the Playwright that CI's accessibility check already installs.
//
//   node devices/email-images.mjs <screens> [only-id] --blog
//
// draws the blog's pictures instead (`devices/site/blog-images.mjs`, the same frames) into
// devices/public/blog/img/posts/<id>.jpg; <screens> then also needs the website's `web-*.png` pictures.
import { createRequire } from "node:module";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const { chromium } = createRequire(import.meta.url)("playwright");
const here = fileURLToPath(new URL(".", import.meta.url));
const blog = process.argv.includes("--blog");
const args = process.argv.slice(2).filter((a) => a !== "--blog");
const out = join(here, "public", ...(blog ? ["blog", "img", "posts"] : ["email"]));
const screens = resolve(args[0] ?? "");
const only = args[1];
if (!args[0] || !existsSync(screens)) {
    console.error("Usage: node devices/email-images.mjs <folder of app screenshots> [id]");
    process.exit(1);
}
const mark = readFileSync(join(here, "public", "icon.svg"), "utf8").replace(/width="512" height="512"/, 'width="100%" height="100%"');

const src = (name) => {
    const path = join(screens, name);
    if (!existsSync(path)) throw new Error(`${name} isn't in ${screens}`);
    return `file://${path}`;
};
const phone = (name, tilt = 0) => `<div class="phone" style="transform:rotate(${tilt}deg)"><img src="${src(name)}"></div>`;
const tv = (name, width = 640) => `<div class="tv" style="width:${width}px"><img src="${src(name)}"></div>`;
const tablet = (name) => `<div class="tablet"><img src="${src(name)}"></div>`;
const browser = (name) =>
    `<div class="browser"><div class="bar"><i></i><i></i><i></i><span>tunedplayer.com/account</span></div><img src="${src(name)}"></div>`;
// The player's screens are its controls over black (no video in a JVM drawing): blended over a made-up "picture".
const player = (name) => `<div class="player"><div class="scene"></div><img src="${src(name)}"></div>`;
const event = (time, name, channel, action) =>
    `<div class="event"><b class="time">${time}</b><div><b>${name}</b><small>${channel}</small></div><span class="tune">${action}</span></div>`;
const stack = (...parts) => `<div class="row">${parts.join("")}</div>`;
const tiles = (items, cls = "") =>
    `<div class="tiles ${cls}">${items.map(([big, small]) => `<div class="tile"><b>${big}</b>${small ? `<span>${small}</span>` : ""}</div>`).join("")}</div>`;

/** Each picture: what's in it, a chip naming the area, and whether it's Premium. */
const IMAGES = {
    welcome: { chip: "Welcome", body: stack(phone("playlist-welcome.png", -3), tv("browse-profiles-tv.png", 600)) },
    instant: {
        chip: "Opens in an instant",
        body: stack(phone("browse-live-tv.png", -3), tiles([["0 s", "waiting on the network before your channels show"]], "one")),
    },
    guide: { chip: "TV guide", body: stack(phone("browse-guide.png", -2), phone("browse-search-guide.png", 3)) },
    rewind: { chip: "Pause and rewind", premium: true, body: player("player-controls.png") },
    events: {
        chip: "Live events",
        body: `<div class="events"><h3>Today</h3>
            ${event("7:00 PM", "Harbour Hawks vs Lakeside Lions", "Sports 1 · 4K", "Tune")}
            ${event("8:30 PM", "Northern Cup: Final", "Sports 3 · HD", "Tune")}
            ${event("10:00 PM", "Main event: Reyes vs Okafor", "PPV 12", "Tune")}
            <h3>Tomorrow</h3>${event("1:00 PM", "Prairie Rovers vs Coast City", "Sports 2 · FHD", "🔔")}</div>`,
    },
    follow: {
        chip: "Follow and alerts",
        premium: true,
        body: `<div class="follow"><div class="chips"><span>Harbour Hawks ✓</span><span>Northern Cup ✓</span><span>Reyes ✓</span></div>
            <div class="note"><div class="mark small">${mark}</div><div><b>Harbour Hawks start in 15 minutes</b><small>vs Lakeside Lions · Tap to tune</small></div></div></div>`,
    },
    library: { chip: "Movies and shows", body: stack(phone("browse-show.png", -3), phone("browse-movie.png", 3)) },
    profiles: {
        chip: "Profiles",
        premium: true,
        body: stack(phone("browse-profiles-named.png", -3), tv("browse-profiles-tv-light.png", 600)),
    },
    cast: { chip: "Cast to your TV", body: player("player-casting.png") },
    "one-stream": {
        chip: "One stream, always",
        body: `<div class="flow">
            <div class="step off"><span class="dot"></span><div><b>Channel 12</b><small>closes first</small></div></div>
            <div class="arrow">→</div>
            <div class="step on"><span class="dot"></span><div><b>Channel 13</b><small>then opens</small></div></div>
        </div>
        <div class="flow small"><div class="step wait"><span class="dot"></span><div><b>Downloads and scans</b><small>wait while you watch</small></div></div></div>
        <p class="big">1 connection. Never 2.</p>`,
    },
    tv: { chip: "Android TV and Google TV", body: stack(tv("playlist-welcome-tv.png", 820)) },
    account: {
        chip: "Tuned account",
        premium: true,
        body: stack(phone("settings-account-sign-in.png", -2), tv("settings-account-sign-in-tv.png", 600)),
    },
    filters: { chip: "Filters that stick", body: stack(phone("browse-live-tv-filters.png", -2)) },
    search: { chip: "Search", body: stack(phone("browse-search.png", -3), phone("browse-search-guide.png", 3)) },
    lists: {
        chip: "Favourites and lists",
        premium: "both",
        body: stack(phone("browse-guide.png", -2), phone("widgets-favourites-sizes.png", 3)),
    },
    "remote-tricks": {
        chip: "Did you know?",
        body: stack(phone("settings-page-channels.png", -2), phone("settings-page-playback.png", 3)),
    },
    speed: {
        chip: "Built for speed",
        body: tiles([
            ["1.9 s → 45 ms", "Opening Live TV on a big playlist"],
            ["20 s → 2.4 s", "Processing 150,000 streams"],
            ["0", "Spinners before your channels"],
        ]),
    },
    downloads: {
        chip: "Downloads",
        premium: "both",
        body: stack(phone("browse-downloads.png", -3), phone("browse-downloads-home.png", 3)),
    },
    web: { chip: "tunedplayer.com", premium: true, body: browser("web-playlists.png") },
    "tv-sign-in": {
        chip: "Set up with your phone",
        body: stack(phone("playlist-send-to-tv.png", -2), tv("settings-account-sign-in-tv.png", 600)),
    },
    captions: { chip: "Captions", body: stack(phone("settings-captions.png", -2)) },
    themes: {
        chip: "Themes and colours",
        premium: true,
        body: stack(phone("settings-light.png", -4), phone("settings-page-appearance.png", 0), phone("widgets-teal.png", 4)),
    },
    reliability: { chip: "When a channel fails", body: stack(phone("settings-page-reliability.png", -2)) },
    tonight: { chip: "What to watch", body: stack(phone("browse-movies-top-rated.png", -3), phone("browse-shows-recently-added.png", 3)) },
    progress: { chip: "Real progress", body: stack(phone("browse-first-download.png", -2), tv("browse-first-download-tv.png", 600)) },
    playlists: {
        chip: "Many playlists",
        premium: true,
        body: stack(phone("playlist-add-xtream.png", -4), phone("playlist-add-link.png", 0), phone("playlist-add-portal.png", 4)),
    },
    widgets: { chip: "Widgets", body: stack(phone("widgets.png", -3), phone("widgets-light.png", 3)) },
    "tv-tips": { chip: "Did you know?", body: stack(phone("settings-lighter-mode.png", -2), tv("language-de-settings-tv.png", 600)) },
    trakt: {
        chip: "Trakt and Simkl",
        premium: true,
        body: `<div class="hub"><div class="logo">${mark}</div><div class="spokes"><div class="pill">Trakt</div><div class="pill">Simkl</div></div></div>
        <p class="big">Scrobbles as you watch. History comes along.</p>`,
    },
    player: { chip: "Out of your way", body: player("player-subtitles-off.png") },
    countries: { chip: "Tidy up", body: stack(phone("settings-countries.png", -3), phone("settings-countries-us-canada.png", 3)) },
    privacy: { chip: "Private by design", body: stack(phone("settings-usage-stats.png", -2), phone("settings-page-about.png", 3)) },
    languages: {
        chip: "11 languages and English",
        body: stack(phone("language-ar-live-tv.png", -4), phone("language-zh-live-tv.png", 0), phone("language-hi-settings.png", 4)),
    },
    "media-servers": {
        chip: "Bring your playlist",
        body: `<div class="hub"><div class="logo">${mark}</div><div class="spokes grid">
            <div class="pill">Plex</div><div class="pill">Jellyfin</div><div class="pill">Emby</div>
            <div class="pill soft">Xtream Codes</div><div class="pill soft">M3U</div><div class="pill soft">MAC portal</div></div></div>`,
    },
    tablets: { chip: "Tablets", body: tablet("settings-tablet.png") },
    "guide-tips": { chip: "Did you know?", body: stack(phone("settings-page-guide.png", -2), phone("browse-guide.png", 3)) },
    help: { chip: "Report a problem", body: stack(phone("settings-report-problem.png", -2)) },
    premium: {
        chip: "Tuned Premium",
        premium: true,
        body: tiles(
            [
                ["⏪", "Pause, rewind and record live TV"],
                ["🔔", "Follow teams, get alerts"],
                ["👪", "Six profiles, kids and PINs"],
                ["📚", "Many playlists and your own lists"],
                ["☁️", "Your account on every device"],
                ["🎨", "Themes, Trakt, Simkl, more downloads"],
            ],
            "six",
        ),
    },
};

const PICTURES = blog
    ? (await import("./site/blog-images.mjs")).blogImages({ phone, tv, tablet, browser, player, event, stack, tiles, mark })
    : IMAGES;

const CSS = `
* { box-sizing: border-box; margin: 0 }
html, body { width: 1200px; height: 760px; overflow: hidden }
body { font-family: Roboto, "Segoe UI", Helvetica, Arial, sans-serif; color: #e6e1ea;
  background: radial-gradient(900px 520px at 85% -10%, rgba(109,74,255,.55), transparent 70%),
              radial-gradient(700px 480px at -10% 110%, rgba(95,220,195,.22), transparent 70%), #121018;
  display: flex; flex-direction: column; align-items: center; justify-content: center; position: relative }
.top { position: absolute; top: 28px; left: 34px; right: 34px; display: flex; align-items: center; gap: 12px }
.top .mark { width: 44px; height: 44px }
.top .name { font-weight: 700; font-size: 26px; letter-spacing: -.01em }
.top .chip { margin-left: auto; padding: 9px 18px; border-radius: 999px; background: rgba(201,190,255,.14); border: 1px solid rgba(201,190,255,.35); color: #e6deff; font-weight: 600; font-size: 20px }
.top .prem { padding: 9px 18px; border-radius: 999px; background: linear-gradient(90deg,#6d4aff,#a24bff); color: #fff; font-weight: 700; font-size: 18px; letter-spacing: .08em; text-transform: uppercase }
.top .free { padding: 9px 18px; border-radius: 999px; background: rgba(95,220,195,.16); border: 1px solid rgba(95,220,195,.45); color: #5fdcc3; font-weight: 700; font-size: 18px; letter-spacing: .06em; text-transform: uppercase }
.stage { margin-top: 70px; display: flex; flex-direction: column; align-items: center; gap: 26px }
.row { display: flex; align-items: center; justify-content: center; gap: 40px }
.phone { width: 268px; aspect-ratio: 1233/2673; border-radius: 36px; padding: 9px; background: #0b0a10; border: 2px solid #3a3644;
  box-shadow: 0 30px 70px rgba(0,0,0,.55), 0 0 0 1px rgba(255,255,255,.04) inset; height: 590px }
.phone img { width: 100%; height: 100%; object-fit: cover; object-position: top; border-radius: 28px; display: block }
.tv { aspect-ratio: 16/9; padding: 12px; border-radius: 16px; background: #0b0a10; border: 2px solid #3a3644; box-shadow: 0 30px 70px rgba(0,0,0,.55); position: relative }
.tv img, .tablet img { width: 100%; height: 100%; object-fit: cover; border-radius: 6px; display: block }
.tv::after { content: ""; position: absolute; left: 50%; bottom: -26px; width: 140px; height: 12px; margin-left: -70px; border-radius: 0 0 10px 10px; background: #2a2733 }
.tablet { width: 900px; aspect-ratio: 2560/1600; padding: 16px; border-radius: 30px; background: #0b0a10; border: 2px solid #3a3644; box-shadow: 0 30px 70px rgba(0,0,0,.55) }
.browser { width: 1000px; height: 590px; border-radius: 16px; overflow: hidden; background: #fcfaff; border: 1px solid #3a3644; box-shadow: 0 30px 70px rgba(0,0,0,.55) }
.browser .bar { height: 40px; background: #29262f; display: flex; align-items: center; gap: 8px; padding: 0 16px }
.browser .bar i { width: 12px; height: 12px; border-radius: 50%; background: #48454f }
.browser .bar span { margin-left: 16px; padding: 4px 16px; border-radius: 999px; background: #1e1c25; color: #cac4d0; font-size: 15px }
.browser img { width: 100%; display: block }
.player { width: 1000px; aspect-ratio: 2673/1233; border-radius: 22px; overflow: hidden; position: relative; border: 2px solid #3a3644; box-shadow: 0 30px 70px rgba(0,0,0,.55) }
.player .scene { position: absolute; inset: 0; background:
  radial-gradient(60% 80% at 70% 40%, #ffb36b, transparent 60%), radial-gradient(50% 60% at 25% 70%, #2f7fff, transparent 65%),
  radial-gradient(40% 50% at 50% 20%, #ff6b9a, transparent 70%), linear-gradient(160deg, #1b2a5e, #120c2c) }
.player .scene::after { content: ""; position: absolute; inset: 0; background: rgba(0,0,0,.35) }
.player img { position: absolute; inset: 0; width: 100%; height: 100%; mix-blend-mode: screen }
.tiles { display: grid; grid-template-columns: repeat(3, 340px); gap: 22px }
.tiles.one { grid-template-columns: 380px }
.tiles.one b { font-size: 120px }
.events { width: 860px; padding: 26px 30px; border-radius: 28px; background: rgba(30,28,37,.95); border: 1px solid #34313a; box-shadow: 0 30px 70px rgba(0,0,0,.5) }
.events h3 { font-size: 20px; text-transform: uppercase; letter-spacing: .08em; color: #c9beff; margin: 6px 0 10px }
.event { display: flex; align-items: center; gap: 24px; padding: 14px 0; border-bottom: 1px solid #34313a }
.event:last-child { border: 0 }
.event .time { width: 120px; font-size: 24px; color: #5fdcc3 }
.event div { flex: 1 } .event div b { display: block; font-size: 26px } .event small { font-size: 19px; color: #cac4d0 }
.tune { padding: 10px 24px; border-radius: 999px; background: #c9beff; color: #2b10a0; font-weight: 700; font-size: 20px }
.follow { display: flex; flex-direction: column; align-items: center; gap: 40px }
.follow .chips { display: flex; gap: 16px } .follow .chips span { padding: 14px 26px; border-radius: 999px; border: 1px solid rgba(201,190,255,.5); background: rgba(109,74,255,.25); font-size: 26px; font-weight: 600 }
.note { display: flex; align-items: center; gap: 22px; width: 780px; padding: 26px 30px; border-radius: 28px; background: #e6e1ea; color: #1b1a21; box-shadow: 0 30px 70px rgba(0,0,0,.5) }
.note b { display: block; font-size: 30px } .note small { font-size: 22px; color: #48454f }
.mark.small { width: 72px; height: 72px; flex: none }
.tiles.six { grid-template-columns: repeat(3, 330px) }
.tile { padding: 30px 26px; border-radius: 24px; background: rgba(30,28,37,.9); border: 1px solid #34313a; min-height: 210px; display: flex; flex-direction: column; justify-content: center; gap: 12px }
.tile b { font-size: 40px; line-height: 1.1; color: #c9beff; letter-spacing: -.02em }
.six .tile { min-height: 190px }
.six .tile b { font-size: 52px }
.tile span { font-size: 24px; line-height: 1.3; color: #e6e1ea }
.flow { display: flex; align-items: center; gap: 28px }
.step { display: flex; align-items: center; gap: 18px; padding: 24px 30px; border-radius: 24px; background: rgba(30,28,37,.9); border: 1px solid #34313a; min-width: 330px }
.step b { display: block; font-size: 32px } .step small { font-size: 22px; color: #cac4d0 }
.dot { width: 22px; height: 22px; border-radius: 50% }
.off .dot { background: #48454f } .on .dot { background: #5fdcc3; box-shadow: 0 0 0 8px rgba(95,220,195,.2) } .wait .dot { background: #ffd479 }
.on { border-color: rgba(95,220,195,.6) }
.arrow { font-size: 54px; color: #c9beff }
.big { font-size: 40px; font-weight: 700; letter-spacing: -.01em; color: #fff; text-align: center }
.hub { display: flex; align-items: center; gap: 60px }
.hub .logo { width: 220px; height: 220px; filter: drop-shadow(0 30px 50px rgba(109,74,255,.5)) }
.spokes { display: flex; flex-direction: column; gap: 22px }
.spokes.grid { display: grid; grid-template-columns: repeat(3, auto); gap: 22px }
.pill { padding: 22px 38px; border-radius: 999px; background: rgba(30,28,37,.95); border: 1px solid rgba(201,190,255,.45); font-size: 36px; font-weight: 700; text-align: center }
.pill.soft { border-color: rgba(95,220,195,.45); color: #5fdcc3; font-size: 30px }
`;

function page(spec) {
    const badge =
        spec.premium === true
            ? `<span class="prem">★ Premium</span>`
            : spec.premium === "both"
              ? `<span class="free">Free</span><span class="prem">+ Premium</span>`
              : "";
    return `<!doctype html><html><head><meta charset="utf-8"><style>${CSS}</style></head><body>
<div class="top"><div class="mark">${mark}</div><div class="name">Tuned</div><span class="chip">${spec.chip}</span>${badge}</div>
<div class="stage">${spec.body}</div></body></html>`;
}

mkdirSync(out, { recursive: true });
const browserApp = await chromium.launch();
const tab = await browserApp.newPage({ viewport: { width: 1200, height: 760 }, deviceScaleFactor: 1 });
const temp = join(screens, ".email-image.html");
for (const [id, spec] of Object.entries(PICTURES)) {
    if (only && id !== only) continue;
    writeFileSync(temp, page(spec));
    await tab.goto(`file://${temp}`);
    await tab.waitForLoadState("networkidle");
    await tab.screenshot({ path: join(out, `${id}.jpg`), type: "jpeg", quality: 80 });
    console.log(join(out, `${id}.jpg`));
}
await browserApp.close();
