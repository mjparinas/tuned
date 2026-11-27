// Draws every preview scene at 1920 × 1080 (960 × 540 dp at 2×) into preview/out/screenshots, for the pull request
// and the store listing (docs/decisions/vega.md). Run after `npm run preview:build`.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { mkdirSync } from "node:fs";
import { extname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const out = fileURLToPath(new URL("./out", import.meta.url));
const shots = join(out, "screenshots");
mkdirSync(shots, { recursive: true });
const SCENES = [
    "signin",
    "signin-offline",
    "manual",
    "loading",
    "loading-failed",
    "live",
    "live-refreshing",
    "player",
    "player-failed",
    "settings",
    "settings-refreshing",
    "movies",
    "movies-loading",
    "shows",
    "movie",
    "show",
    "movie-player",
    "live-favourites",
    "search",
    "movie-details",
    "movies-sorted",
    "movies-continue",
    "settings-playlists",
    "live-guide",
    "reminder",
    "events",
    "events-reading",
    "tune-trying",
    "tune-found",
    "tune-none",
    "profiles",
    "profiles-pin",
    "profiles-pin-wrong",
    "live-kids",
    "settings-profile",
    "live-lists",
    "add-to-list",
    "add-to-list-new",
    "movie-lists",
    "settings-lists",
    "settings-simkl-code",
    "settings-trakt-code",
];
// A few scenes in other languages: German for long words, Arabic right to left, Chinese for another script.
const LANGUAGE_SCENES = ["de-DE", "ar-EG", "zh-CN"].flatMap((lang) =>
    ["live", "movies", "show", "settings"].map((scene) => ({ scene, lang })),
);
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".png": "image/png" };

const server = createServer(async (req, res) => {
    const path = new URL(req.url, "http://x").pathname;
    try {
        const body = await readFile(join(out, path === "/" ? "index.html" : path));
        res.writeHead(200, { "Content-Type": types[extname(path)] ?? "text/html" }).end(body);
    } catch {
        res.writeHead(404).end();
    }
}).listen(0);
const port = server.address().port;
// CHROMIUM_PATH: a Chromium already on the machine, when Playwright's own download isn't there.
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const page = await browser.newPage({ viewport: { width: 960, height: 540 }, deviceScaleFactor: 2 });
const failures = [];
page.on("pageerror", (e) => failures.push(e.message));
for (const scene of SCENES) {
    await page.goto(`http://localhost:${port}/?scene=${scene}`);
    await page.waitForTimeout(400);
    // Settings › Your lists is further down the page: scroll to it, as the remote would.
    const below = { "settings-lists": "Your lists", "settings-simkl-code": "Trakt", "settings-trakt-code": "Trakt" }[scene];
    if (below) {
        await page.getByText(below, { exact: true }).evaluate((e) => e.scrollIntoView({ block: "start" }));
        await page.waitForTimeout(100);
    }
    await page.screenshot({ path: join(shots, `vega-${scene}.png`) });
}
for (const { scene, lang } of LANGUAGE_SCENES) {
    await page.goto(`http://localhost:${port}/?scene=${scene}&lang=${lang}`);
    await page.waitForTimeout(400);
    await page.screenshot({ path: join(shots, `vega-${lang.slice(0, 2)}-${scene}.png`) });
}
await browser.close();
server.close();
if (failures.length) {
    console.error(failures.join("\n"));
    process.exit(1);
}
console.log(`${SCENES.length + LANGUAGE_SCENES.length} screenshots in ${shots}`);
