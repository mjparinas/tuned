// Simkl on a TV: sign-in with a code, token renewal, scrobbling and the watched history, against a stand-in Simkl.
import assert from "node:assert/strict";
import { test } from "node:test";
import { MemoryStore } from "./platform.ts";
import { readHistory, Simkl, simklFromBackup, simklProgress, simklScrobbleFromBackup, type SignInCode } from "./simkl.ts";
import { fakeFetch } from "./test-fetch.ts";

const DAY = 24 * 3600_000;

function setup(answers: Record<string, (body: string) => { status?: number; body: unknown } | undefined>) {
    let now = 1_000_000;
    const store = new MemoryStore();
    const logged: string[] = [];
    const fetch = fakeFetch((url, init) => {
        assert.equal(url.searchParams.get("client_id"), "cid");
        assert.equal(url.searchParams.get("app-name"), "tuned");
        return answers[`${init.method ?? "GET"} ${url.pathname}`]?.(init.body ?? "");
    });
    const simkl = new Simkl({ fetch, store, clientId: "cid", appVersion: "1.0", log: (_, m) => logged.push(m), now: () => now });
    return { simkl, store, fetch, logged, tick: (ms: number) => (now += ms), at: () => now };
}

test("signs in with a code: waits, slows down, then keeps the tokens and the account name", async () => {
    const polls = [
        { status: 400, body: { error: "authorization_pending" } },
        { status: 400, body: { error: "slow_down" } },
    ];
    const { simkl, store, logged } = setup({
        "POST /oauth2/device": (body) => {
            assert.equal(body, "client_id=cid&scope=media%3Aread%20media%3Awrite");
            return {
                body: { device_code: "dev", user_code: "AB12CD", verification_uri: "https://simkl.com/pin", expires_in: 900, interval: 5 },
            };
        },
        "POST /oauth2/token": (body) => {
            assert.match(body, /device_code=dev/);
            return polls.shift() ?? { body: { access_token: "acc-secret", refresh_token: "ref-secret", expires_in: 90 * 86400 } };
        },
        "GET /users/settings": () => ({ body: { user: { name: "Robin" } } }),
    });
    const code = (await simkl.startSignIn()) as SignInCode;
    assert.equal(code.userCode, "AB12CD");
    assert.equal(code.verificationUrl, "https://simkl.com/pin");
    assert.equal(code.intervalMs, 5000);
    assert.equal(Simkl.slowDown(code.intervalMs), 10_000);
    assert.equal(await simkl.pollSignIn(code), "waiting");
    assert.equal(await simkl.pollSignIn(code), "slow down");
    assert.deepEqual(await simkl.pollSignIn(code), { signedIn: true, user: "Robin" });
    assert.deepEqual(await simkl.state(), { signedIn: true, user: "Robin" });
    assert.match((await store.get("simkl.token")) ?? "", /acc-secret/);
    // A fresh copy reads the kept sign-in.
    assert.deepEqual(await new Simkl({ fetch: fakeFetch(() => undefined), store, clientId: "cid" }).state(), {
        signedIn: true,
        user: "Robin",
    });
    assert.equal(logged.join("\n").includes("secret"), false);
});

test("a code that ran out is expired without asking Simkl", async () => {
    const { simkl, fetch, tick } = setup({});
    const code: SignInCode = { userCode: "X", deviceCode: "d", verificationUrl: "u", expiresAt: 1_000_500, intervalMs: 5000 };
    tick(1000);
    assert.equal(await simkl.pollSignIn(code), "expired");
    assert.equal(fetch.calls.length, 0);
});

test("scrobbles a movie with its cleaned name, skips tiny spots, and renews a token near its end", async () => {
    const sent: unknown[] = [];
    let renewals = 0;
    const { simkl, tick } = setup({
        "POST /oauth2/token": (body) => {
            renewals++;
            assert.match(body, /grant_type=refresh_token&client_id=cid&refresh_token=r1/);
            return { body: { access_token: "a2", expires_in: 90 * 86400 } };
        },
        "POST /scrobble/start": (body) => (sent.push(JSON.parse(body)), { status: 201, body: {} }),
        "POST /scrobble/stop": (body) => (sent.push(JSON.parse(body)), { status: 409, body: {} }),
    });
    assert.equal(await simkl.scrobble("start", { type: "movie", title: "Paper Boats" }, 0), "signed out");
    await simkl.adopt({ access: "a1", refresh: "r1", expiresAt: 1_000_000 + 2 * DAY, username: "Robin" });
    assert.equal(await simkl.scrobble("start", { type: "movie", title: "EN | Paper Boats (2025) 4K" }, 0), "ok");
    assert.deepEqual(sent[0], { movie: { title: "Paper Boats", year: 2025 }, progress: 0 });
    assert.equal(await simkl.scrobble("pause", { type: "movie", title: "Paper Boats" }, 0.4), "ok");
    assert.equal(sent.length, 1);
    tick(1.5 * DAY);
    // Two at once renew once.
    const both = await Promise.all([
        simkl.scrobble("stop", { type: "episode", show: "Harbour Street", season: 2, episode: 3, tmdbId: 77 }, 91.5),
        simkl.scrobble("stop", { type: "episode", show: "Harbour Street", season: 2, episode: 3 }, 91.5),
    ]);
    assert.deepEqual(both, ["ok", "ok"]);
    assert.equal(renewals, 1);
    assert.deepEqual(sent[1], { show: { title: "Harbour Street", ids: { tmdb: 77 } }, episode: { season: 2, number: 3 }, progress: 91.5 });

    await simkl.setScrobble(false);
    assert.equal(await simkl.scrobble("start", { type: "movie", title: "Paper Boats" }, 0), "ok");
    assert.equal(sent.length, 3);
});

test("a refresh Simkl refuses signs out; the network failing is offline and never throws", async () => {
    const { simkl } = setup({ "POST /oauth2/token": () => ({ status: 401, body: {} }) });
    await simkl.adopt({ access: "a", refresh: "r", expiresAt: 1_000_000, username: null });
    assert.equal(await simkl.scrobble("start", { type: "movie", title: "Paper Boats" }, 0), "signed out");
    assert.deepEqual(await simkl.state(), { signedIn: false, user: null });

    const down = new Simkl({
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

test("takes a newer sign-in from the account's backup, never an older one", async () => {
    const { simkl } = setup({});
    const backup = (expiresAt: number) =>
        `{"fingerprint":"f","copy":{"profiles":[],"simkl":{"access":"a${expiresAt}","refresh":"r","expiresAt":${expiresAt},"username":"Robin"}}}`;
    assert.equal(simklFromBackup('{"copy":{}}'), null);
    assert.equal(simklFromBackup("not json"), null);
    assert.equal(simklScrobbleFromBackup('{"copy":{"simklScrobble":false}}'), false);
    assert.equal(simklScrobbleFromBackup('{"copy":{}}'), null);
    assert.equal(await simkl.adopt(simklFromBackup(backup(5 * DAY))), true);
    assert.equal(await simkl.adopt(simklFromBackup(backup(4 * DAY))), false);
    assert.deepEqual(await simkl.state(), { signedIn: true, user: "Robin" });
});

test("signing out tells Simkl to drop the token; forgetting only clears this device", async () => {
    const revoked: string[] = [];
    const { simkl } = setup({ "POST /oauth2/revoke": (body) => (revoked.push(body), { body: {} }) });
    await simkl.adopt({ access: "a", refresh: "r9", expiresAt: 9 * DAY, username: null });
    await simkl.signOut();
    assert.deepEqual(revoked, ["client_id=cid&token=r9"]);
    assert.deepEqual(await simkl.state(), { signedIn: false, user: null });
    await simkl.adopt({ access: "a", refresh: "r9", expiresAt: 9 * DAY, username: null });
    await simkl.forget();
    assert.equal(revoked.length, 1);
    assert.equal((await simkl.state()).signedIn, false);
});

test("reads completed movies and every watched episode, anime by its TVDB numbers", async () => {
    const { simkl } = setup({
        "GET /sync/all-items/movies/completed": () => ({
            body: {
                movies: [
                    { last_watched_at: "2026-11-01T20:00:00Z", movie: { title: "Paper Boats", year: 2025, ids: { tmdb: 12 } } },
                    { movie: {} },
                ],
            },
        }),
        "GET /sync/all-items/shows": () => ({
            body: {
                shows: [
                    {
                        last_watched_at: "2026-11-02T20:00:00Z",
                        show: { title: "Harbour Street", year: 2024 },
                        seasons: [{ number: 1, episodes: [{ number: 1, watched_at: "2026-10-01T20:00:00Z" }, { number: 2 }] }],
                    },
                ],
            },
        }),
        "GET /sync/all-items/anime": () => ({
            body: {
                anime: [
                    { show: { title: "Sky Kites" }, seasons: [{ number: 1, episodes: [{ number: 14, tvdb: { season: 2, episode: 1 } }] }] },
                ],
            },
        }),
    });
    await simkl.adopt({ access: "a", refresh: "r", expiresAt: 9 * DAY, username: null });
    const rows = await simkl.watchedHistory();
    assert.deepEqual(rows, [
        { title: "Paper Boats", year: 2025, tmdbId: 12, watchedAt: Date.parse("2026-11-01T20:00:00Z") },
        { title: "Harbour Street", year: 2024, watchedAt: Date.parse("2026-10-01T20:00:00Z"), season: 1, episode: 1 },
        { title: "Harbour Street", year: 2024, watchedAt: Date.parse("2026-11-02T20:00:00Z"), season: 1, episode: 2 },
        { title: "Sky Kites", watchedAt: 1_000_000, season: 2, episode: 1 },
    ]);
    assert.deepEqual(readHistory("{}", 0), []);
    assert.deepEqual(readHistory("", 0), []);
});

test("progress is a percentage to two places", () => {
    assert.equal(simklProgress(0, 0), 0);
    assert.equal(simklProgress(1234, 6000), 20.57);
    assert.equal(simklProgress(9000, 6000), 100);
});
