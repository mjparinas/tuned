// The website's accessibility check (docs/decisions/website.md › Accessibility and standards; Melvin, 2026-11-20:
// "check the website for wcag, accessability, and other web standards"). CI runs it on every change to devices/:
// `node devices/a11y-check.mjs`. It serves devices/public as Vercel does, opens every page in Chromium in light and
// dark with reduced motion, and runs axe-core's WCAG 2.2 A and AA rules and its best practices. The account page is
// checked signed out and signed in (with a made-up account and library), and the admin with admin-preview.mjs's sample data.
// It also checks that no page scrolls sideways at 320 px wide (WCAG 1.4.10) and that everything Tab reaches shows a
// focus ring (2.4.7). Any finding fails, with the page, the rule and the elements.
import { spawn } from "node:child_process";
import { readFile, readdir, stat } from "node:fs/promises";
import { createServer } from "node:http";
import { createRequire } from "node:module";
import { extname, join } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const axeSource = await readFile(require.resolve("axe-core/axe.min.js"), "utf8");

const here = fileURLToPath(new URL(".", import.meta.url));
const root = join(here, "public");
const TYPES = {
    ".html": "text/html",
    ".js": "text/javascript",
    ".mjs": "text/javascript",
    ".css": "text/css",
    ".svg": "image/svg+xml",
    ".webp": "image/webp",
    ".png": "image/png",
    ".ico": "image/x-icon",
    ".json": "application/json",
    ".xml": "application/xml",
    ".txt": "text/plain",
};
const day = 86_400_000;
const sampleAccount = {
    status: "ok",
    name: "Sam Example",
    email: "sam@example.com",
    premium: "yearly",
    seats: { used: 2, limit: 3, canBuy: 5, seatKind: "seat_yearly" },
    devices: [
        { id: 1, name: "Pixel 8", kind: "phone", seated: true, lastSeen: Date.now() - day },
        { id: 2, name: "Living room", kind: "tv", seated: true, lastSeen: Date.now() - 3 * day },
    ],
    purchases: [{ source: "stripe" }],
    backup: { savedAt: Date.now() - day, device: "Pixel 8" },
};
const webSettings = JSON.parse(await readFile(join(fileURLToPath(new URL(".", import.meta.url)), "lib/web-settings.json"), "utf8"));
const sampleLibrary = {
    status: "ok",
    exists: true,
    playlists: [
        {
            id: "a",
            kind: "xtream",
            name: "Living room",
            detail: "provider.example",
            server: "http://provider.example",
            username: "sam",
            mac: "",
        },
        { id: "b", kind: "link", name: "", detail: "lists.example", server: "", username: "", mac: "" },
    ],
    settings: webSettings.map((g) => ({ group: g.group, settings: g.settings.map((s) => ({ ...s, value: s.default })) })),
};

/** devices/public as Vercel serves it: folders to index.html, `/tv` to tv/index.html, the API answering signed out. */
function serve(port, { signedIn }) {
    return new Promise((resolve) => {
        const server = createServer(async (req, res) => {
            const path = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
            if (path === "/api/account/me" && signedIn) {
                res.writeHead(200, { "Content-Type": "application/json" });
                return res.end(JSON.stringify(sampleAccount));
            }
            if (path === "/api/account/library" && signedIn) {
                res.writeHead(200, { "Content-Type": "application/json" });
                return res.end(JSON.stringify(sampleLibrary));
            }
            if (path.startsWith("/api/")) {
                res.writeHead(200, { "Content-Type": "application/json" });
                return res.end(JSON.stringify({ status: "signed out" }));
            }
            let file = join(root, path);
            try {
                if ((await stat(file)).isDirectory()) file = join(file, "index.html");
            } catch {
                file = (await stat(`${file}.html`).catch(() => null)) ? `${file}.html` : join(file, "index.html");
            }
            try {
                const body = await readFile(file);
                res.writeHead(200, { "Content-Type": TYPES[extname(file)] ?? "application/octet-stream" });
                res.end(body);
            } catch {
                res.writeHead(404);
                res.end("Not found");
            }
        }).listen(port, () => resolve(server));
    });
}

/** Every page: the generated ones (each index.html under public/) and the hand-written ones. */
async function pages() {
    const found = [];
    async function walk(dir, url) {
        for (const entry of await readdir(dir, { withFileTypes: true })) {
            if (entry.isDirectory()) {
                if (!["admin", "img", "vendor"].includes(entry.name)) await walk(join(dir, entry.name), `${url}${entry.name}/`);
            } else if (entry.name === "index.html") found.push(url);
        }
    }
    await walk(root, "/");
    return [...found, "/app/signed-in.html"];
}

async function waitFor(url) {
    for (let i = 0; i < 50; i++) {
        if (
            await fetch(url).then(
                (r) => r.ok,
                () => false,
            )
        )
            return;
        await new Promise((r) => setTimeout(r, 200));
    }
    throw new Error(`${url} didn't start`);
}

const findings = [];
const note = (where, what) => findings.push(`${where}: ${what}`);

async function axe(page, where) {
    if (!(await page.evaluate(() => "axe" in window))) await page.addScriptTag({ content: axeSource });
    const result = await page.evaluate(() =>
        // eslint-disable-next-line no-undef
        axe.run(document, {
            runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"] },
        }),
    );
    for (const v of result.violations) {
        const nodes = v.nodes
            .slice(0, 3)
            .map((n) => `${n.target.join(" ")}${n.any[0]?.message ? ` (${n.any[0].message})` : ""}`)
            .join("; ");
        note(where, `${v.id}: ${v.help}, ${v.nodes.length} element(s): ${nodes}`);
    }
}

/** At 320 px nothing scrolls sideways, and each element Tab reaches shows an outline or ring. */
async function reflowAndFocus(page, where) {
    const wide = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (wide > 1) note(where, `scrolls sideways by ${wide}px at 320px wide (WCAG 1.4.10)`);
    for (let i = 0; i < 60; i++) {
        await page.keyboard.press("Tab");
        const focus = await page.evaluate(() => {
            const e = document.activeElement;
            if (!e || e === document.body) return null;
            const s = getComputedStyle(e);
            const ring =
                (s.outlineStyle !== "none" && parseFloat(s.outlineWidth) > 0) ||
                s.boxShadow !== "none" ||
                [...document.querySelectorAll(":focus-within")].some(
                    (p) =>
                        p !== e &&
                        p.contains(e) &&
                        getComputedStyle(p).outlineStyle !== "none" &&
                        p !== document.documentElement &&
                        p !== document.body,
                );
            return {
                ring,
                name: `${e.tagName.toLowerCase()}${e.id ? `#${e.id}` : ""} "${(e.getAttribute("aria-label") || e.textContent || "").trim().slice(0, 30)}"`,
            };
        });
        if (!focus) break;
        if (!focus.ring) note(where, `${focus.name} shows no focus ring (WCAG 2.4.7)`);
    }
}

const site = await serve(4190, { signedIn: false });
const signedIn = await serve(4191, { signedIn: true });
const admin = spawn(process.execPath, [join(here, "admin-preview.mjs")], { stdio: "ignore" });
const browser = await chromium.launch();
try {
    await waitFor("http://localhost:4173/admin");
    const targets = [
        ...(await pages()).map((p) => ({ name: p, url: `http://localhost:4190${p}` })),
        { name: "/account (signed in)", url: "http://localhost:4191/account/" },
        { name: "/account/playlists (signed in)", url: "http://localhost:4191/account/playlists/" },
        { name: "/admin", url: "http://localhost:4173/admin", sections: true },
    ];
    // Eight pages at a time, so the whole site takes well under a minute.
    const pool = async (items, run) => {
        const queue = [...items];
        await Promise.all(
            Array.from({ length: 8 }, async () => {
                while (queue.length) await run(queue.shift());
            }),
        );
    };
    const light = await browser.newContext({ colorScheme: "light", reducedMotion: "reduce" });
    const dark = await browser.newContext({ colorScheme: "dark", reducedMotion: "reduce" });
    const phone = await browser.newContext({ viewport: { width: 320, height: 640 }, reducedMotion: "reduce" });
    const jobs = targets.flatMap((t) => [
        { t, context: light, scheme: "light" },
        { t, context: dark, scheme: "dark" },
        { t, context: phone, scheme: "320px" },
    ]);
    await pool(jobs, async ({ t, context, scheme }) => {
        const page = await context.newPage();
        try {
            await page.goto(t.url, { waitUntil: "networkidle" });
            if (scheme === "320px") return await reflowAndFocus(page, `${t.name} (320px)`);
            await axe(page, `${t.name} (${scheme})`);
            // The admin draws each of its sections when its button in the side menu is pressed.
            if (t.sections) {
                const buttons = page.locator(".nav button");
                for (let i = 0; i < (await buttons.count()); i++) {
                    const label = (await buttons.nth(i).innerText()).split("\n")[0];
                    await buttons.nth(i).click();
                    await page.waitForLoadState("networkidle");
                    await axe(page, `${t.name} › ${label} (${scheme})`);
                }
            }
        } catch (e) {
            note(`${t.name} (${scheme})`, `couldn't be checked: ${e.message}`);
        } finally {
            await page.close();
        }
    });
    const unique = [...new Set(findings)];
    if (unique.length) {
        console.error(`${unique.length} accessibility finding(s):\n${unique.map((f) => `  ${f}`).join("\n")}`);
        process.exitCode = 1;
    } else console.log(`Accessibility: ${targets.length} pages in light and dark, no findings.`);
} finally {
    await browser.close();
    admin.kill();
    site.close();
    signedIn.close();
}
