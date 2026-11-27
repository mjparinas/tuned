// Trakt on a TV: sign-in with a code, token renewal, scrobbling and the watched history, against a stand-in Trakt.
import assert from "node:assert/strict";
import { test } from "node:test";
import { MemoryStore } from "./platform.ts";
import { fakeFetch } from "./test-fetch.ts";
import {
    pollAnswer,
    readWatchedShows,
    type SignInCode,
    Trakt,
    traktFromBackup,
    traktProgress,
    traktScrobbleFromBackup,
    WATCHED_PAGE,
} from "./trakt.ts";

const DAY = 24 * 3600_000;

function setup(answers: Record<string, (body: string, url: URL) => { status?: number; body: unknown } | undefined>) {
    let now = 1_000_000;
    const store = new MemoryStore();
    const logged: string[] = [];
    const fetch = fakeFetch((url, init) => {
        assert.equal(init.headers?.["trakt-api-version"], "2");
        assert.equal(init.headers?.["trakt-api-key"], "cid");
        return answers[`${init.method ?? "GET"} ${url.pathname}`]?.(init.body ?? "", url);
    });
    const trakt = new Trakt({ fetch, store, clientId: "cid", log: (_, m) => logged.push(m), now: () => now });
    return { trakt, store, fetch, logged, tick: (ms: number) => (now += ms) };
}

test("signs in with a code: waits, slows down, then keeps the tokens and the account name", async () => {
    const polls = [
        { status: 400, body: {} },
        { status: 429, body: {} },
    ];
    const { trakt, store, logged } = setup({
        "POST /oauth/device/code": (body) => {
            assert.deepEqual(JSON.parse(body), { client_id: "cid" });
            return {
                body: {
                    device_code: "dev",
                    user_code: "AB12CD",
                    verification_url: "https://trakt.tv/activate",
                    expires_in: 600,
                    interval: 5,
                },
            };
        },
        "POST /oauth/device/token": (body) => {
            // The id alone: Trakt issues no secret, and an empty one is refused.
            assert.deepEqual(JSON.parse(body), { code: "dev", client_id: "cid" });
            return (
                polls.shift() ?? {
                    body: { access_token: "acc-secret", refresh_token: "ref-secret", expires_in: 90 * 86400, created_at: 2000 },
                }
            );
        },
        "GET /users/settings": () => ({ body: { user: { username: "robin" } } }),
    });
    const code = (await trakt.startSignIn()) as SignInCode;
    assert.equal(code.userCode, "AB12CD");
    assert.equal(code.verificationUrl, "https://trakt.tv/activate");
    assert.equal(code.intervalMs, 5000);
    assert.equal(Trakt.slowDown(code.intervalMs), 6000);
    assert.equal(await trakt.pollSignIn(code), "waiting");
    assert.equal(await trakt.pollSignIn(code), "slow down");
    assert.deepEqual(await trakt.pollSignIn(code), { signedIn: true, user: "robin" });
    const kept = JSON.parse((await store.get("trakt.token")) ?? "{}");
    assert.equal(kept.expiresAt, (2000 + 90 * 86400) * 1000);
    assert.deepEqual(await new Trakt({ fetch: fakeFetch(() => undefined), store, clientId: "cid" }).state(), {
        signedIn: true,
        user: "robin",
    });
    assert.equal(logged.join("\n").includes("secret"), false);
});

test("poll answers follow Android's", () => {
    assert.equal(pollAnswer(200), "signed in");
    assert.equal(pollAnswer(400), "waiting");
    assert.equal(pollAnswer(429), "slow down");
    assert.equal(pollAnswer(404), "expired");
    assert.equal(pollAnswer(410), "expired");
    assert.equal(pollAnswer(418), "denied");
    assert.equal(pollAnswer(409), "denied");
});

test("a code that ran out is expired without asking Trakt; no id means no Trakt", async () => {
    const { trakt, fetch, tick } = setup({});
    const code: SignInCode = { userCode: "X", deviceCode: "d", verificationUrl: "u", expiresAt: 1_000_500, intervalMs: 5000 };
    tick(1000);
    assert.equal(await trakt.pollSignIn(code), "expired");
    assert.equal(fetch.calls.length, 0);
    const none = new Trakt({ fetch, store: new MemoryStore(), clientId: "" });
    assert.equal(none.available, false);
    assert.equal(await none.startSignIn(), null);
});

test("scrobbles with the cleaned name, skips tiny spots, and renews a token near its end once", async () => {
    const sent: unknown[] = [];
    let renewals = 0;
    const { trakt, tick } = setup({
        "POST /oauth/token": (body) => {
            renewals++;
            assert.deepEqual(JSON.parse(body), { refresh_token: "r1", client_id: "cid", grant_type: "refresh_token" });
            return { body: { access_token: "a2", refresh_token: "r2", expires_in: 90 * 86400 } };
        },
        "POST /scrobble/start": (body) => (sent.push(JSON.parse(body)), { status: 201, body: {} }),
        "POST /scrobble/stop": (body) => (sent.push(JSON.parse(body)), { status: 409, body: {} }),
    });
    assert.equal(await trakt.scrobble("start", { type: "movie", title: "Paper Boats" }, 0), "signed out");
    await trakt.adopt({ access: "a1", refresh: "r1", expiresAt: 1_000_000 + 2 * DAY, username: "robin" });
    assert.equal(await trakt.scrobble("start", { type: "movie", title: "EN | Paper Boats (2025) 4K" }, 0), "ok");
    assert.deepEqual(sent[0], { movie: { title: "Paper Boats", year: 2025 }, progress: 0 });
    assert.equal(await trakt.scrobble("pause", { type: "movie", title: "Paper Boats" }, 0.44), "ok");
    assert.equal(sent.length, 1);
    tick(1.5 * DAY);
    const both = await Promise.all([
        trakt.scrobble("stop", { type: "episode", show: "Harbour Street", season: 2, episode: 3, tmdbId: 77 }, 91.5),
        trakt.scrobble("stop", { type: "episode", show: "Harbour Street", season: 2, episode: 3 }, 91.5),
    ]);
    assert.deepEqual(both, ["ok", "ok"]);
    assert.equal(renewals, 1);
    assert.deepEqual(sent[1], { show: { title: "Harbour Street", ids: { tmdb: 77 } }, episode: { season: 2, number: 3 }, progress: 91.5 });
    await trakt.setScrobble(false);
    assert.equal(await trakt.scrobble("start", { type: "movie", title: "Paper Boats" }, 0), "ok");
    assert.equal(sent.length, 3);
});

test("a refresh Trakt refuses signs out; the network failing is offline and never throws", async () => {
    const { trakt } = setup({ "POST /oauth/token": () => ({ status: 401, body: {} }) });
    await trakt.adopt({ access: "a", refresh: "r", expiresAt: 1_000_000, username: null });
    assert.equal(await trakt.scrobble("start", { type: "movie", title: "Paper Boats" }, 0), "signed out");
    assert.deepEqual(await trakt.state(), { signedIn: false, user: null });

    const down = new Trakt({
        fetch: async () => {
            throw new TypeError("Network request failed");
        },
        store: new MemoryStore(),
        clientId: "cid",
    });
    await down.adopt({ access: "a", refresh: "r", expiresAt: Date.now() + 30 * DAY, username: null });
    assert.equal(await down.scrobble("start", { type: "movie", title: "Paper Boats" }, 0), "offline");
    assert.equal(await down.watchedHistory(), null);
});

test("takes a newer sign-in and the scrobble switch from the account's backup, never an older sign-in", async () => {
    const { trakt } = setup({});
    const backup = (expiresAt: number) =>
        `{"fingerprint":"f","copy":{"profiles":[],"traktScrobble":false,"trakt":{"access":"a${expiresAt}","refresh":"r","expiresAt":${expiresAt},"username":"robin"}}}`;
    assert.equal(traktFromBackup('{"copy":{}}'), null);
    assert.equal(traktFromBackup("not json"), null);
    assert.equal(traktScrobbleFromBackup('{"copy":{}}'), null);
    assert.equal(traktScrobbleFromBackup(backup(1)), false);
    assert.equal(await trakt.adopt(traktFromBackup(backup(5 * DAY))), true);
    assert.equal(await trakt.adopt(traktFromBackup(backup(4 * DAY))), false);
    assert.deepEqual(await trakt.state(), { signedIn: true, user: "robin" });
});

test("signing out tells Trakt to drop the token; forgetting only clears this device", async () => {
    const revoked: unknown[] = [];
    const { trakt } = setup({ "POST /oauth/revoke": (body) => (revoked.push(JSON.parse(body)), { body: {} }) });
    await trakt.adopt({ access: "a9", refresh: "r9", expiresAt: 9 * DAY, username: null });
    await trakt.signOut();
    assert.deepEqual(revoked, [{ token: "a9", client_id: "cid" }]);
    assert.deepEqual(await trakt.state(), { signedIn: false, user: null });
    await trakt.adopt({ access: "a9", refresh: "r9", expiresAt: 9 * DAY, username: null });
    await trakt.setScrobble(false);
    await trakt.forget();
    assert.equal(revoked.length, 1);
    assert.equal((await trakt.state()).signedIn, false);
    assert.equal(await trakt.scrobbleOn(), true);
});

test("reads every watched movie page by page, and every episode of the watched shows", async () => {
    const pages: string[] = [];
    const fullPage = Array.from({ length: WATCHED_PAGE }, (_, i) => ({ movie: { title: `Film ${i}` } }));
    const { trakt } = setup({
        "GET /sync/watched/movies": (_, url) => {
            pages.push(`${url.searchParams.get("page")}/${url.searchParams.get("limit")}`);
            return url.searchParams.get("page") === "1"
                ? { body: fullPage }
                : {
                      body: [
                          { last_watched_at: "2026-11-01T20:00:00Z", movie: { title: "Paper Boats", year: 2025, ids: { tmdb: 12 } } },
                          { movie: {} },
                      ],
                  };
        },
        "GET /sync/watched/shows": (_, url) => {
            assert.equal(url.searchParams.get("extended"), "progress");
            assert.equal(url.searchParams.get("limit"), "100");
            return {
                body: [
                    {
                        last_watched_at: "2026-11-02T20:00:00Z",
                        show: { title: "Harbour Street", year: 2024 },
                        seasons: [{ number: 1, episodes: [{ number: 1, last_watched_at: "2026-10-01T20:00:00Z" }, { number: 2 }] }],
                    },
                ],
            };
        },
    });
    await trakt.adopt({ access: "a", refresh: "r", expiresAt: 9 * DAY, username: null });
    const rows = (await trakt.watchedHistory()) ?? [];
    assert.deepEqual(pages, ["1/250", "2/250"]);
    assert.equal(rows.length, WATCHED_PAGE + 3);
    assert.deepEqual(rows.slice(WATCHED_PAGE), [
        { title: "Paper Boats", year: 2025, tmdbId: 12, watchedAt: Date.parse("2026-11-01T20:00:00Z") },
        { title: "Harbour Street", year: 2024, watchedAt: Date.parse("2026-10-01T20:00:00Z"), season: 1, episode: 1 },
        { title: "Harbour Street", year: 2024, watchedAt: Date.parse("2026-11-02T20:00:00Z"), season: 1, episode: 2 },
    ]);
    assert.equal(readWatchedShows("not json", 0), null);
    assert.deepEqual(readWatchedShows("", 0), { rows: [], count: 0 });
});

test("a history page Trakt refuses fails the whole import rather than importing half", async () => {
    const { trakt, logged } = setup({
        "GET /sync/watched/movies": () => ({ body: [] }),
        "GET /sync/watched/shows": () => ({ status: 502, body: "Bad gateway" }),
    });
    await trakt.adopt({ access: "a", refresh: "r", expiresAt: 9 * DAY, username: null });
    assert.equal(await trakt.watchedHistory(), null);
    assert.match(logged.join("\n"), /502/);
});

test("progress is a percentage to two places", () => {
    assert.equal(traktProgress(0, 0), 0);
    assert.equal(traktProgress(1234, 6000), 20.57);
    assert.equal(traktProgress(9000, 6000), 100);
});
