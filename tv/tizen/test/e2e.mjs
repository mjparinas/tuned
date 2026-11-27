// The Samsung TV app end to end in desktop Chromium, driven with the remote's keys: the welcome screen and its code,
// typing a playlist link, the numbered first download, Live TV with the guide, playing, channel up, Back, then Movies,
// Shows and Settings. A local stand-in serves the playlist, the guide, three short clips and the sign-in relay. It
// checks there was never more than one stream connection, in the app and at the server, and saves a picture of each
// screen in test/out/.
import { execFileSync } from "node:child_process";
import { pbkdf2Sync } from "node:crypto";
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

/** Android's id for a stream (`StableId`): FNV-1a 64 of kind, link tail and name, as signed decimal text. */
function androidId(kind, url, name) {
    const tail = url
        .split("?")[0]
        .split("/")
        .pop()
        .replace(/\.[^.]*$/, "");
    let hash = 0xcbf29ce484222325n;
    for (const byte of Buffer.from(`${kind}|${tail}|${name}`)) hash = ((hash ^ BigInt(byte)) * 0x100000001b3n) & 0xffffffffffffffffn;
    return BigInt.asIntN(64, hash).toString();
}
/** A PIN hashed as Android's `PinHash` does. */
const pinHash = (pin) => {
    const salt = Buffer.from("tuned-e2e-salt!!");
    return `v1:${salt.toString("base64")}:${pbkdf2Sync(pin, salt, 120_000, 32, "sha256").toString("base64")}`;
};
/**
 * The account's backup as the phone left it: a grown-up profile with a PIN and a list for game night, a kids one whose
 * favourite is Treehouse, and CBC News renamed on the website.
 */
const backup = (base) =>
    `{"fingerprint":"f1","changedAt":1,"copy":{"settings":{"adult_pin":{"s":"${pinHash("4321")}"}},` +
    `"channelEdits":[{"itemId":${androidId(0, `${base}/live/0.webm`, "CBC News")},"name":"CBC National","logo":null}],"profiles":[` +
    `{"id":1,"name":"Sam","colour":1,"kids":false,"pinHash":"${pinHash("1234")}","createdAt":1,"avatar":"fox",` +
    `"lists":[{"id":31,"name":"Game night","position":1,"createdAt":1,"items":[{"itemId":${androidId(0, `${base}/live/1.webm`, "TSN 1")},"addedAt":1,"position":1}]}]},` +
    `{"id":2,"name":"Robin","colour":5,"kids":true,"createdAt":2,"avatar":"dragon","favourites":[{"itemId":${androidId(0, `${base}/live/2.webm`, "Treehouse")},"addedAt":1,"position":0}]}]}}`;
/** The backup as saved last (the TV saves its changes on top), and its version. */
let saved = null;
let savedVersion = 1;
/** Once true, the phone signs the TV in to the account as soon as the code shows. */
let phoneSignsIn = false;

let open = 0;
let highest = 0;
/** What catch-up recordings the app asked for. */
const replays = [];
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
    // CBC News keeps three days of recordings, as a Kodi-style playlist says so (playlist.md › Catch-up).
    const catchUp = `catchup="default" catchup-days="3" catchup-source="${base}/catchup/{utc}-{duration}.webm"`;
    channels.forEach(([id, name, group], i) =>
        lines.push(
            `#EXTINF:-1 tvg-id="${id}" tvg-name="${name}" ${i === 0 ? catchUp + " " : ""}group-title="${group}",${name}`,
            `${base}/live/${i}.webm`,
        ),
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
    // Something CBC News showed earlier, which its recordings can replay.
    xml += `<programme channel="news.ca" start="${xmltvTime(now - 9000_000)}" stop="${xmltvTime(now - 1800_000)}"><title>Morning Show</title></programme>`;
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
        return res.end(JSON.stringify(phoneSignsIn ? { status: "ok", link: "one-time" } : { status: "waiting" }));
    }
    const signedIn = req.headers.authorization === "Bearer tv-token";
    res.setHeader("Content-Type", "application/json");
    if (url.pathname === "/api/auth/token")
        return res.end(JSON.stringify({ status: "ok", token: "tv-token", email: "viewer@example.com" }));
    if (url.pathname === "/api/account/seat")
        return signedIn ? res.end(JSON.stringify({ seat: "active", email: "viewer@example.com" })) : res.writeHead(401).end();
    if (url.pathname === "/api/backup") {
        if (!signedIn) return res.writeHead(401).end();
        saved ??= backup(base);
        if (req.method !== "PUT") return res.writeHead(200, { "X-Backup-Version": String(savedVersion) }).end(saved);
        let body = "";
        req.on("data", (c) => (body += c));
        req.on("end", () => {
            if (req.headers["if-match"] !== `"${savedVersion}"`) return res.writeHead(409).end(JSON.stringify({ status: "conflict" }));
            saved = body;
            savedVersion++;
            res.end(JSON.stringify({ status: "ok", version: savedVersion }));
        });
        return;
    }
    res.removeHeader("Content-Type");
    if (url.pathname === "/get.m3u") return res.end(playlist(base));
    if (url.pathname === "/guide.xml") return res.end(guide());
    const stream = url.pathname.match(/^\/(live|movie|series|catchup)\/(.+)$/);
    if (stream) {
        if (stream[1] === "catchup") replays.push(stream[2]);
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
const errors = [];
/** A fresh TV (nothing saved) whose language is `locale`; Tizen hands the TV's menu language to the page the same way. */
const freshTv = async (locale) => {
    const tv = await browser.newPage({ viewport: { width: 1920, height: 1080 }, locale });
    tv.on("pageerror", (e) => errors.push(String(e)));
    tv.on("console", (m) => process.env.VERBOSE && console.log("page:", m.text()));
    return tv;
};
let page = await freshTv("en-CA");

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
/** A key only a Samsung remote has (the colour keys), sent with its Tizen code. */
const remote = async (code) => {
    await page.evaluate((c) => {
        const event = new Event("keydown", { bubbles: true, cancelable: true });
        Object.defineProperty(event, "keyCode", { value: c });
        document.dispatchEvent(event);
    }, code);
    await page.waitForTimeout(150);
};
const GREEN = 404;
const YELLOW = 405;
const BLUE = 406;
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
    await step("Green shows the guide grid, and a past programme replays from its recording", async () => {
        await remote(GREEN);
        await page.locator(".grid").waitFor();
        await page.locator(".prog.on").getByText("The National").waitFor({ timeout: 5000 });
        await page.waitForTimeout(300);
        await shot("8-guide-grid");
        await key("ArrowLeft");
        await page.locator(".prog.on").getByText("Morning Show").waitFor({ timeout: 5000 });
        await page.getByText("OK plays it from the start").waitFor();
        await page.waitForTimeout(300);
        await shot("9-catch-up");
        await key("Enter");
        await page.waitForFunction(() => document.querySelector("video")?.currentTime > 0.5, null, { timeout: 15_000 });
        const start = Math.floor((now - 9000_000) / 1000);
        if (replays[replays.length - 1] !== `${start}-7200.webm`) throw new Error(`Asked for the recording ${replays.join(", ")}`);
        await page.waitForTimeout(5500);
        await key("Escape");
        await page.locator(".player").waitFor({ state: "detached", timeout: 5000 });
        await remote(GREEN);
        await page.locator(".channel").first().waitFor();
        await page.waitForTimeout(300);
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
    // The same TV in other languages (docs/decisions/languages.md): German for long words, Arabic drawn right to left,
    // Chinese for another script. Each adds the playlist by its link, as above, then shows Live TV and Settings.
    for (const [locale, words] of [
        ["de-DE", { settings: "Einstellungen", channelNumbers: "Sendernummern" }],
        ["ar-EG", { settings: "الإعدادات", channelNumbers: "أرقام القنوات" }],
        ["zh-CN", { settings: "设置", channelNumbers: "频道号" }],
    ]) {
        await step(`In ${locale}`, async () => {
            const app = await page.evaluate(() => window.__tuned.connections());
            if (app.highest > 1) throw new Error("More than one stream connection was open at once");
            await page.close();
            page = await freshTv(locale);
            const tag = locale.slice(0, 2);
            // Arabic is mirrored, so what sits to the right in English sits to the left, and the remote follows it.
            const [toward, away] = tag === "ar" ? ["ArrowLeft", "ArrowRight"] : ["ArrowRight", "ArrowLeft"];
            await page.goto(base);
            await page.getByText("K7QF-29MD").waitFor({ timeout: 10_000 });
            await page.waitForTimeout(300);
            await shot(`${tag}-1-welcome`);
            await key("Enter");
            await page.locator("input").first().waitFor();
            await page.waitForTimeout(200);
            await key(toward);
            await key("Enter");
            await key("ArrowDown");
            await page.keyboard.type(`${base}/get.m3u`);
            await key("ArrowDown");
            await page.keyboard.type(`${base}/guide.xml`);
            await key("ArrowDown");
            await key("Enter");
            await page.locator(".channel").first().waitFor({ timeout: 20_000 });
            await page.getByText("The National").first().waitFor({ timeout: 10_000 });
            await page.waitForTimeout(300);
            await shot(`${tag}-2-live-tv`);
            await key(away, 4);
            for (let i = 0; i < 4 && !(await focused()).includes(words.settings); i++) await key("ArrowDown");
            await page.getByText(words.channelNumbers).waitFor();
            await page.waitForTimeout(300);
            await shot(`${tag}-3-settings`);
        });
    }
    await step("Who's watching, a kids profile, and a PIN", async () => {
        await page.close();
        page = await freshTv("en-CA");
        phoneSignsIn = true;
        await page.goto(base);
        await page.getByText("Signed in to your Tuned account.").waitFor({ timeout: 10_000 });
        await page.waitForTimeout(300);
        await page.getByText("Type a login with the remote").click();
        await page.getByText("Xtream login").waitFor();
        await page.waitForTimeout(200);
        await key("ArrowRight");
        await key("Enter");
        await key("ArrowDown");
        await page.keyboard.type(`${base}/get.m3u`);
        await key("ArrowDown");
        await page.keyboard.type(`${base}/guide.xml`);
        await key("ArrowDown");
        await key("Enter");
        await page.getByText("Who's watching?").waitFor({ timeout: 20_000 });
        await page.waitForTimeout(300);
        await shot("10-whos-watching");
        if (!(await focused()).includes("Sam")) throw new Error(`Focus is on "${await focused()}"`);
        await key("ArrowRight");
        await key("Enter");
        await page.locator(".channel").first().waitFor({ timeout: 10_000 });
        await page.getByText("Favourites").first().waitFor({ timeout: 5000 });
        const names = await page.locator(".channel .name").allTextContents();
        if (names.join() !== "Treehouse") throw new Error(`A kids profile sees ${names.join(", ")}`);
        if (await page.locator(".rail").getByText("Settings").count()) throw new Error("A kids profile has Settings");
        await page.waitForTimeout(300);
        await shot("11-kids-profile");
        await key("ArrowLeft", 3);
        for (let i = 0; i < 6 && !(await focused()).includes("Robin"); i++) await key("ArrowDown");
        await key("Enter");
        await page.getByText("Who's watching?").waitFor();
        await page.waitForTimeout(300);
        await key("ArrowLeft");
        await key("Enter");
        await page.getByText("Enter Sam's PIN").waitFor();
        await page.keyboard.type("1111");
        await page.waitForTimeout(200);
        await key("Enter");
        await page.getByText("That PIN isn't right.").waitFor({ timeout: 10_000 });
        await page.waitForTimeout(300);
        await shot("12-pin");
        await page.keyboard.type("1234");
        await page.waitForTimeout(200);
        await key("Enter");
        await page.getByText("Who's watching?").waitFor({ state: "detached", timeout: 10_000 });
        await page.locator(".channel").nth(3).waitFor({ timeout: 10_000 });
        phoneSignsIn = false;
    });
    await step("Your own lists, a renamed channel, and Favourites in the account", async () => {
        await page.getByText("CBC National").first().waitFor({ timeout: 5000 });
        await page.locator(".chip", { hasText: "Game night" }).waitFor({ timeout: 5000 });
        await key("ArrowRight");
        for (let i = 0; i < 4 && !(await focused()).includes("CBC National"); i++) await key("ArrowUp");
        if (!(await focused()).includes("CBC National")) throw new Error(`Focus is on "${await focused()}"`);
        await remote(BLUE);
        await page.getByText("Add CBC National to a list").waitFor();
        await page.waitForTimeout(300);
        await shot("13-add-to-list");
        const cbc = androidId(0, `${base}/live/0.webm`, "CBC News");
        await key("Enter");
        await page.locator('.dialog.lists [role="checkbox"][aria-checked="true"]').waitFor({ timeout: 10_000 });
        if (!saved.includes(`{"itemId":${cbc},`)) throw new Error("Game night didn't get CBC News in the account");
        await key("ArrowDown");
        await key("Enter");
        await page.getByPlaceholder("List name").waitFor();
        await page.keyboard.type("Evening");
        await key("ArrowDown");
        await key("Enter");
        await page.locator(".dialog.lists .row", { hasText: "Evening" }).waitFor({ timeout: 10_000 });
        if (!saved.includes('"name":"Evening"')) throw new Error("The new list isn't in the account");
        await key("Escape");
        await page.locator(".dialog.lists").waitFor({ state: "detached" });
        await remote(YELLOW);
        await page.locator(".chip", { hasText: "Evening" }).waitFor({ timeout: 10_000 });
        await page.waitForTimeout(1500);
        const copy = JSON.parse(saved.replace(/(?<=[:\[,])-?\d{16,}/g, (d) => `"${d}"`)).copy;
        if (!copy.profiles[0].favourites?.some((f) => f.itemId === cbc)) throw new Error("Yellow didn't add a favourite to the account");
        await page.waitForTimeout(300);
        await shot("14-lists-on-live-tv");
        await key("ArrowLeft", 3);
        for (let i = 0; i < 6 && !(await focused()).includes("Settings"); i++) await key("ArrowDown");
        await key("Enter");
        await page.getByText("Your lists").waitFor();
        await page.waitForTimeout(300);
        await shot("15-your-lists");
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
