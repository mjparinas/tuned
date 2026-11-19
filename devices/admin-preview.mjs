// The admin with made-up data, for a look and for screenshots: `node devices/admin-preview.mjs`, then open
// http://localhost:4173/admin. Signed in as an admin who has passed the second step. Changes are accepted and
// forgotten. Not deployed: Vercel only serves public/ and api/.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";
import { adminApi } from "./lib/admin/api.mjs";
import { sampleData, sampleSystem } from "./lib/admin/sample.mjs";

const root = join(fileURLToPath(new URL(".", import.meta.url)), "public");
const role = process.env.PREVIEW_ROLE ?? "admin";
const email = role === "admin" ? "melvin@example.com" : "help@example.com";
const session = { email, role, verified: true, started: Date.now(), used: Date.now() };
const ok = async () => [];
const handle = adminApi({
    data: new Proxy(sampleData, { get: (t, k) => t[k] ?? ok }),
    redis: { command: async () => 0 },
    env: { ADMIN_EMAILS: "melvin@example.com", SUPPORT_EMAILS: "help@example.com", RESEND_API_KEY: "x" },
    sessions: { read: async () => ({ token: "t", session }), create: async () => ({}), update: async () => {}, end: async () => "" },
    auth: { webAccount: async () => null, account: async () => null, googleStart: async () => "/", signInReady: () => true },
    system: sampleSystem,
    mail: { send: async () => true },
});
const TYPES = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml" };

createServer(async (req, res) => {
    const url = new URL(req.url, "http://localhost:4173");
    if (url.pathname.startsWith("/api/admin/")) {
        const chunks = [];
        for await (const c of req) chunks.push(c);
        const request = new Request(url, {
            method: req.method,
            headers: { ...req.headers, origin: "http://localhost:4173" },
            body: req.method === "POST" ? Buffer.concat(chunks) : undefined,
        });
        const response = await handle(request, url.pathname.split("/")[3]);
        res.writeHead(response.status, { "Content-Type": "application/json" });
        return res.end(await response.text());
    }
    const path = url.pathname === "/admin" ? "/admin/index.html" : url.pathname;
    try {
        const file = await readFile(join(root, normalize(path).replace(/^(\.\.[/\\])+/, "")));
        res.writeHead(200, { "Content-Type": TYPES[extname(path)] ?? "application/octet-stream" });
        res.end(file);
    } catch {
        res.writeHead(404);
        res.end("Not found");
    }
}).listen(4173, () => console.log("Admin preview on http://localhost:4173/admin"));
