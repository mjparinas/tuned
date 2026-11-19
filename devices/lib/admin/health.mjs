// The System tab's checks (docs/decisions/admin.md): each answers { state: green | amber | red, ... } and a failure
// in one never hides the others.
import { settingsState } from "./system.mjs";

const REPO = "mjparinas/tuned";

async function timed(work) {
    const started = Date.now();
    try {
        return { ...(await work()), ms: Date.now() - started };
    } catch (error) {
        return { state: "red", error: String(error?.message ?? error).slice(0, 200), ms: Date.now() - started };
    }
}

async function github(path, env, fetcher) {
    const headers = { Accept: "application/vnd.github+json", "User-Agent": "tuned-admin" };
    if (env.GITHUB_TOKEN) headers.Authorization = `Bearer ${env.GITHUB_TOKEN}`;
    const response = await fetcher(`https://api.github.com/repos/${REPO}${path}`, { headers });
    if (!response.ok) throw new Error(`GitHub answered ${response.status}`);
    return response.json();
}

const run = (r) =>
    r
        ? { status: r.status, conclusion: r.conclusion, branch: r.head_branch, at: r.updated_at, url: r.html_url, title: r.display_title }
        : null;
const runState = (r) => (!r ? "amber" : r.conclusion === "success" || r.status !== "completed" ? "green" : "red");

/** Every check, for `GET /api/admin/system`. */
export function systemHealth({ data, redis, env = process.env, fetcher = fetch }) {
    return async () => {
        const [database, cache, ci, releases] = await Promise.all([
            timed(async () => {
                const h = await data.databaseHealth();
                return { state: h.ms > 1500 ? "amber" : "green", bytes: h.bytes, limitBytes: 512 * 1024 * 1024 };
            }),
            timed(async () => ({ state: (await redis.command("PING")) === "PONG" ? "green" : "red" })),
            timed(async () => {
                const [staging, main] = await Promise.all(
                    ["staging", "main"].map((b) =>
                        github(`/actions/workflows/ci.yml/runs?branch=${b}&per_page=1`, env, fetcher).then((j) => j.workflow_runs?.[0]),
                    ),
                );
                const state = [runState(staging), runState(main)].includes("red") ? "red" : "green";
                return { state, staging: run(staging), main: run(main) };
            }),
            timed(async () => {
                const [release, runs] = await Promise.all([
                    github(`/releases/latest`, env, fetcher).catch(() => null),
                    github(`/actions/workflows/release.yml/runs?per_page=1`, env, fetcher).then((j) => j.workflow_runs?.[0]),
                ]);
                return {
                    state: runState(runs),
                    version: release?.tag_name ?? null,
                    publishedAt: release?.published_at ?? null,
                    last: run(runs),
                };
            }),
        ]);
        const failures = Number((await redis.command("GET", `adminfail:${new Date().toISOString().slice(0, 10)}`).catch(() => 0)) ?? 0);
        const settings = settingsState(env);
        return {
            database,
            redis: cache,
            ci,
            releases,
            deploy: {
                state: "green",
                env: env.VERCEL_ENV ?? "local",
                commit: (env.VERCEL_GIT_COMMIT_SHA ?? "").slice(0, 7) || null,
                branch: env.VERCEL_GIT_COMMIT_REF ?? null,
                region: env.VERCEL_REGION ?? null,
            },
            security: {
                state: failures >= 5 ? "amber" : "green",
                failedToday: failures,
                twoFactor: env.ADMIN_2FA === "optional" ? "optional" : "required",
            },
            settings: { state: settings.some((s) => s.required && s.state !== "set") ? "amber" : "green", list: settings },
        };
    };
}
