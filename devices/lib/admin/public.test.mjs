import { test } from "node:test";
import assert from "node:assert/strict";
import { publicApi } from "./public.mjs";

function world(env = { ANALYTICS_SECRET: "salt" }) {
    const counts = new Map();
    const saved = { batches: [], views: [], reports: [], redeemed: [] };
    const redis = {
        async command(op, k) {
            if (op === "INCR") {
                counts.set(k, (counts.get(k) ?? 0) + 1);
                return counts.get(k);
            }
            return "OK";
        },
    };
    const data = {
        saveBatch: async (b, c) => saved.batches.push({ ...b, country: c }),
        savePageView: async (v, visitor, c) => saved.views.push({ ...v, visitor, country: c }),
        saveReport: async (r) => saved.reports.push(r) && 7,
        flagRows: async () => [{ key: "cast", value: false, share: 100 }],
        redeem: async (code, account) => saved.redeemed.push({ code, account }) && { status: "ok" },
    };
    const auth = { webAccount: async (r) => (r.headers.get("cookie") === "s=1" ? "acct-1" : null) };
    return { api: publicApi({ data, redis, env, auth }), saved };
}

const post = (path, body, headers = {}) =>
    new Request(`https://tuned.example${path}`, {
        method: "POST",
        headers: { "x-vercel-ip-country": "CA", ...headers },
        body: JSON.stringify(body),
    });
const install = "0123456789abcdef0123456789abcdef";
const chrome = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36";

test("a batch is cleaned, saved with the country, and limited per address", async () => {
    const { api, saved } = world();
    const ok = await api.collect(
        post("/api/collect", { install, device: { build: "play" }, events: [{ name: "app_open", props: { cold: true } }] }),
    );
    assert.equal(ok.status, 200);
    assert.equal(saved.batches[0].country, "CA");
    assert.equal((await api.collect(post("/api/collect", { install: "x" }))).status, 400);
    for (let i = 0; i < 119; i++) await api.collect(post("/api/collect", { install }));
    assert.equal((await api.collect(post("/api/collect", { install }))).status, 429);
});

test("page views never keep the address, and need the salt", async () => {
    const { api, saved } = world();
    const r = await api.pv(post("/api/pv", { path: "/tv" }, { "user-agent": chrome, "x-forwarded-for": "203.0.113.9" }));
    assert.equal(r.status, 204);
    assert.equal(saved.views.length, 1);
    assert.doesNotMatch(JSON.stringify(saved.views), /203\.0\.113\.9/);
    const unsalted = world({});
    await unsalted.api.pv(post("/api/pv", { path: "/tv" }, { "user-agent": chrome }));
    assert.equal(unsalted.saved.views.length, 0);
});

test("flags answer every flag with the admin's changes", async () => {
    const { api } = world();
    const body = await (await api.flags(new Request(`https://tuned.example/api/flags?install=${install}`))).json();
    assert.equal(body.flags.cast, false);
    assert.equal(body.flags.tmdb, true);
});

test("redeeming needs a signed-in account and our own page", async () => {
    const { api, saved } = world();
    assert.equal((await api.redeem(post("/api/codes/redeem", { code: "launch" }, { origin: "https://tuned.example" }))).status, 401);
    assert.equal(
        (await api.redeem(post("/api/codes/redeem", { code: "launch" }, { origin: "https://evil.example", cookie: "s=1" }))).status,
        400,
    );
    const ok = await api.redeem(post("/api/codes/redeem", { code: " launch " }, { origin: "https://tuned.example", cookie: "s=1" }));
    assert.equal(ok.status, 200);
    assert.deepEqual(saved.redeemed, [{ code: "LAUNCH", account: "acct-1" }]);
});

test("a report needs words or a crash", async () => {
    const { api, saved } = world();
    assert.equal((await api.report(post("/api/report", { what: "" }))).status, 400);
    assert.equal((await api.report(post("/api/report", { what: "No guide", install }))).status, 200);
    assert.equal(saved.reports[0].what, "No guide");
    assert.equal((await api.report(post("/api/report", { what: "", details: "Model: Shield", log: "E/x" }))).status, 200);
    assert.equal(saved.reports[1].details, "Model: Shield");
});
