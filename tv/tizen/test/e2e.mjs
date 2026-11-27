// The Samsung TV app end to end in desktop Chromium, driven with the remote's keys: the welcome screen and its code,
// typing a playlist link, the numbered first download, Live TV with the guide, playing, channel up, Back, then Movies,
// Shows and Settings. A local stand-in serves the playlist, the guide, three short clips and the sign-in relay. It
// checks there was never more than one stream connection, in the app and at the server, and saves a picture of each
// screen in test/out/.
import { execFileSync } from "node:child_process";
import { createReadStream, existsSync, mkdirSync, readFileSync, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join } from "node:path";
import { chromium } from "playwright";

const root = new URL("..", import.meta.url).pathname;
const out = join(root, "test/out");
mkdirSync(out, { recursive: true });
if (!existsSync(join(root, "dist/index.html"))) throw new Error("Run `npm run build` first.");

// Three clips of a few seconds, each a different picture; WebM, which every Chromium plays.
const colours = ["testsrc", "smptebars", "rgbtestsrc"];
colours.forEach((src, i) => {
    const file = join(out, `clip${i}.webm`);
    if (!existsSync(file))
        execFileSync("ffmpeg", [
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            `${src}=size=1280x720:rate=25`,
            "-t",
            "60",
            "-pix_fmt",
            "yuv420p",
            "-c:v",
            "libvpx",
            "-b:v",
            "1M",
            file,
        ]);
});

let open = 0;
let highest = 0;
const now = Date.now();
const xmltvTime = (ms) => new Date(ms).toISOString().replace(/[-:T]/g, "").slice(0, 14) + " +0000";

function playlist(base) {
    const lines = ["#EXTM3U"];
    const channels = [
        ["news.ca", "CBC News", "News"],
        ["sport.ca", "TSN 1", "Sports"],
        ["kids.ca", "Treehouse", "Kids"],
        ["docs.ca", "Documentary Channel", "News"],
    ];
    channels.forEach(([id, name, group], i) =>
        lines.push(`#EXTINF:-1 tvg-id="${id}" tvg-name="${name}" group-title="${group}",${name}`, `${base}/live/${i}.webm`),
    );
    for (const [i, movie] of ["The Long Field (2021)", "Northern Lights (2019)", "Harbour Town (2023)"].entries())
        lines.push(`#EXTINF:-1 group-title="Movies Drama",${movie}`, `${base}/movie/${i}.mp4`);
    for (const ep of ["Coast Guard S01 E01", "Coast Guard S01 E02", "Coast Guard S02 E01"])
        lines.push(`#EXTINF:-1 group-title="Series Drama",${ep}`, `${base}/series/${ep.replace(/\s/g, "")}.mkv`);
    return lines.join("\n");
}

function guide() {
    const shows = {
        "news.ca": ["The National", "Morning Live"],
        "sport.ca": ["Hockey Night", "SportsCentre"],
        "kids.ca": ["Cartoons", "Story Time"],
    };
    let xml = '<?xml version="1.0"?><tv>';
    for (const id of Object.keys(shows)) xml += `<channel id="${id}"><display-name>${id}</display-name></channel>`;
    for (const [id, [a, b]] of Object.entries(shows)) {
        xml += `<programme channel="${id}" start="${xmltvTime(now - 1800_000)}" stop="${xmltvTime(now + 1800_000)}"><title>${a}</title><desc>Tonight's top stories from across the country.</desc></programme>`;
        xml += `<programme channel="${id}" start="${xmltvTime(now + 1800_000)}" stop="${xmltvTime(now + 5400_000)}"><title>${b}</title></programme>`;
    }
    return xml + "</tv>";
}

const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".png": "image/png", ".xml": "text/xml" };

const server = createServer((req, res) => {
    const url = new URL(req.url, "http://localhost");
    const base = `http://localhost:${server.address().port}`;
    if (url.pathname === "/api/tv/code") {
        res.setHeader("Content-Type", "application/json");
        return res.end(JSON.stringify({ status: "ok", code: "K7QF29MD", secret: "s".repeat(32), seconds: 600 }));
    }
    if (url.pathname === "/api/tv/take") {
        res.setHeader("Content-Type", "application/json");
        return res.end(JSON.stringify({ status: "waiting" }));
    }
    if (url.pathname === "/api/account/seat") return res.writeHead(401).end();
    if (url.pathname === "/get.m3u") return res.end(playlist(base));
    if (url.pathname === "/guide.xml") return res.end(guide());
    const stream = url.pathname.match(/^\/(live|movie|series)\/(.+)$/);
    if (stream) {
        const n = Number(stream[2].match(/\d/)?.[0] ?? 0) % 3;
        const file = join(out, `clip${n}.webm`);
        open++;
        highest = Math.max(highest, open);
        let closed = false;
        const done = () => {
            if (!closed) open--;
            closed = true;
        };
        res.on("close", done);
        res.writeHead(200, { "Content-Type": "video/webm", "Content-Length": statSync(file).size });
        return createReadStream(file).pipe(res);
    }
    const path = join(root, "dist", url.pathname === "/" ? "index.html" : url.pathname);
    if (!path.startsWith(join(root, "dist")) || !existsSync(path)) return res.writeHead(404).end();
    res.setHeader("Content-Type", types[extname(path)] ?? "application/octet-stream");
    res.end(readFileSync(path));
});
await new Promise((resolve) => server.listen(0, resolve));
const base = `http://localhost:${server.address().port}`;

const browser = await chromium.launch({
    args: ["--autoplay-policy=no-user-gesture-required"],
    // A Chromium already on the machine, when Playwright's own download isn't there.
    executablePath: process.env.CHROMIUM_PATH || undefined,
});
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));
page.on("console", (m) => process.env.VERBOSE && console.log("page:", m.text()));

const step = async (what, work) => {
    process.stdout.write(`${what}… `);
    await work();
    console.log("ok");
};
const shot = (name) => page.screenshot({ path: join(out, `${name}.png`) });
const key = async (k, times = 1) => {
    for (let i = 0; i < times; i++) {
        await page.keyboard.press(k);
        await page.waitForTimeout(120);
    }
};
const focused = () => page.evaluate(() => document.activeElement?.textContent ?? "");

try {
    await page.goto(base);
    await step("Welcome screen shows a code", async () => {
        await page.getByText("K7QF-29MD").waitFor({ timeout: 10_000 });
        await page.waitForTimeout(300);
        await shot("1-welcome");
    });
    await step("Type a playlist link with the remote", async () => {
        if (!(await focused()).includes("Type a login")) throw new Error(`Focus is on "${await focused()}"`);
        await key("Enter");
        await page.getByText("Xtream login").waitFor();
        await page.waitForTimeout(200);
        await key("ArrowRight");
        await key("Enter");
        await key("ArrowDown");
        await page.keyboard.type(`${base}/get.m3u`);
        await key("ArrowDown");
        await page.keyboard.type(`${base}/guide.xml`);
        await shot("2-type-login");
        await key("ArrowDown");
        if (!(await focused()).includes("Add playlist")) throw new Error(`Focus is on "${await focused()}"`);
        await key("Enter");
    });
    await step("Live TV with the guide", async () => {
        await page.locator(".channel").first().waitFor({ timeout: 20_000 });
        await page.getByText("The National").first().waitFor({ timeout: 10_000 });
        await page.waitForTimeout(300);
        await shot("3-live-tv");
        if (!(await focused()).includes("CBC News")) throw new Error(`Focus is on "${await focused()}"`);
    });
    await step("OK plays the channel", async () => {
        await key("Enter");
        await page.waitForFunction(() => document.querySelector("video")?.currentTime > 0.5, null, { timeout: 15_000 });
        await page.waitForTimeout(500);
        await shot("4-player");
    });
    await step("Channel up changes channel", async () => {
        await key("PageUp");
        await page.locator(".banner").getByText("TSN 1").waitFor();
        await page.waitForFunction(() => document.querySelector("video")?.currentTime > 0.5, null, { timeout: 15_000 });
        await key("PageUp", 3);
        await page.waitForFunction(() => document.querySelector("video")?.currentTime > 0.5, null, { timeout: 15_000 });
    });
    await step("Back hides the controls, then stops", async () => {
        await page.waitForTimeout(5500);
        await key("Escape");
        await page.locator(".player").waitFor({ state: "detached", timeout: 5000 });
        await page.waitForTimeout(300);
        const playing = await page.evaluate(() => document.body.classList.contains("playing"));
        if (playing) throw new Error("Still playing after Back");
    });
    await step("Movies", async () => {
        await key("ArrowLeft", 3);
        await key("ArrowDown");
        await page.locator(".poster").first().waitFor();
        await page.waitForTimeout(400);
        await shot("5-movies");
    });
    await step("Shows and a show's episodes", async () => {
        await key("ArrowDown");
        await page.getByText("Coast Guard").first().waitFor();
        await page.waitForTimeout(300);
        await key("ArrowRight");
        await key("ArrowDown");
        if (!(await focused()).includes("Coast Guard")) throw new Error(`Down left the grid for "${await focused()}"`);
        await key("Enter");
        await page.getByText("Season 2").waitFor({ timeout: 5000 });
        await page.waitForTimeout(300);
        await shot("6-show");
        await key("Escape");
    });
    await step("Settings", async () => {
        await key("ArrowLeft", 4);
        for (let i = 0; i < 4 && !(await focused()).includes("Settings"); i++) await key("ArrowDown");
        await page.getByText("Channel numbers").waitFor();
        await page.waitForTimeout(300);
        await shot("7-settings");
    });
    const app = await page.evaluate(() => window.__tuned.connections());
    console.log(`Stream connections at once: app ${app.highest}, server ${highest}`);
    if (app.highest > 1 || highest > 1) throw new Error("More than one stream connection was open at once");
    if (errors.length) throw new Error(`Page errors:\n${errors.join("\n")}`);
    console.log(`Pictures in ${out}`);
} catch (error) {
    await shot("failure").catch(() => undefined);
    console.error(`\n${error.stack ?? error}`);
    process.exitCode = 1;
} finally {
    await browser.close();
    server.close();
}
