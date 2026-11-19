// The endpoints the app and the website call for the admin's numbers (docs/decisions/admin.md). Each limits how often
// one address may call it (the address kept only hashed, for the hour) and answers 503 on any failure of ours, which
// the app takes as "try later". Over `deps`: { data, redis, env, auth }.
import { createHash } from "node:crypto";
import { cleanBatch, cleanPageView, cleanReport, countryOf, visitorHash } from "./analytics.mjs";
import { flagsFor } from "./flags.mjs";
import { normalizeCode } from "./codes.mjs";

const json = (body, status = 200, headers = {}) => Response.json(body, { status, headers: { "Cache-Control": "no-store", ...headers } });
const ipOf = (request) => (request.headers.get("x-forwarded-for") ?? "").split(",")[0].trim();
const sha = (t) => createHash("sha256").update(t).digest("hex");
const LIMITS = { collect: 120, report: 10, pv: 300, flags: 120, redeem: 10 };

async function body(request) {
    try {
        return await request.json();
    } catch {
        return null;
    }
}

export function publicApi({ data, redis, env = process.env, auth }) {
    async function overLimit(request, what) {
        const key = `rl:admin-${what}:${sha(ipOf(request))}`;
        const count = await redis.command("INCR", key);
        if (count === 1) await redis.command("EXPIRE", key, 3600);
        return count > LIMITS[what];
    }

    const guarded = (work) => async (request) => {
        try {
            return await work(request);
        } catch (error) {
            console.error(error);
            return json({ status: "unavailable" }, 503);
        }
    };

    return {
        /** POST /api/collect from the app: one install's details and a batch of allow-listed events. */
        collect: guarded(async (request) => {
            const batch = cleanBatch(await body(request));
            if (!batch) return json({ status: "bad request" }, 400);
            if (await overLimit(request, "collect")) return json({ status: "too many" }, 429);
            await data.saveBatch(batch, countryOf(request));
            return json({ status: "ok", saved: batch.events.length });
        }),

        /** GET /api/flags?install=<id>: every flag's value for this install. */
        flags: guarded(async (request) => {
            if (await overLimit(request, "flags")) return json({ status: "too many" }, 429);
            const install = new URL(request.url).searchParams.get("install");
            const flags = flagsFor(await data.flagRows(), /^[0-9a-f]{32}$/.test(install ?? "") ? install : null);
            return json({ status: "ok", flags }, 200, { "Cache-Control": "private, max-age=300" });
        }),

        /** POST /api/report from Settings › Report a problem. */
        report: guarded(async (request) => {
            const report = cleanReport(await body(request));
            if (!report) return json({ status: "bad request" }, 400);
            if (await overLimit(request, "report")) return json({ status: "too many" }, 429);
            const id = await data.saveReport(report);
            return json({ status: "ok", id: String(id) });
        }),

        /** POST /api/pv from /beacon.js on the website's pages. Answers 204 either way, so a page never waits on it. */
        pv: guarded(async (request) => {
            const view = cleanPageView(await body(request), request);
            if (!view || !env.ANALYTICS_SECRET) return new Response(null, { status: 204 });
            if (await overLimit(request, "pv")) return new Response(null, { status: 204 });
            const day = new Date().toISOString().slice(0, 10);
            const visitor = visitorHash(ipOf(request), request.headers.get("user-agent") ?? "", day, env.ANALYTICS_SECRET);
            await data.savePageView(view, visitor, countryOf(request));
            return new Response(null, { status: 204 });
        }),

        /** POST /api/codes/redeem { code } from the signed-in account page. */
        redeem: guarded(async (request) => {
            const origin = request.headers.get("origin");
            if (!origin || origin !== new URL(request.url).origin) return json({ status: "bad request" }, 400);
            const accountId = await auth.webAccount(request);
            if (!accountId) return json({ status: "signed out" }, 401);
            const code = normalizeCode((await body(request))?.code);
            if (!code) return json({ status: "not found" }, 404);
            if (await overLimit(request, "redeem")) return json({ status: "too many" }, 429);
            const result = await data.redeem(code, accountId);
            return json(result, result.status === "ok" ? 200 : 409);
        }),
    };
}
