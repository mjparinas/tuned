import { test } from "node:test";
import assert from "node:assert/strict";
import {
    applyChange,
    buildPlaylist,
    checkLogin,
    encodeSetting,
    fromOurPage,
    libraryView,
    normalizeMac,
    normalizePortal,
    parseLink,
    playlistId,
    REFRESH_KEY,
    WEB_SETTINGS,
} from "./library.mjs";

const xtream = { server: "http://a.example:8080", username: "me", password: "secret" };
const stored = (playlists, settings) => ({
    fingerprint: "f",
    changedAt: 1,
    copy: { version: 2, playlists, ...(settings ? { settings } : {}) },
});

test("a pasted get.php link becomes its Xtream login, as in the app", () => {
    assert.deepEqual(parseLink("http://a.example:8080/get.php?username=me&password=p%40ss&type=m3u_plus"), {
        kind: "xtream",
        server: "http://a.example:8080",
        username: "me",
        password: "p@ss",
    });
    assert.deepEqual(parseLink("b.example/list.m3u"), { kind: "link", url: "http://b.example/list.m3u" });
    assert.equal(parseLink("not a link"), null);
    assert.equal(parseLink("ftp://b.example/x"), null);
    assert.equal(parseLink("nodots"), null);
});

test("MAC addresses and portals are written the app's way", () => {
    assert.equal(normalizeMac("00-1a-79-ab-cd-ef"), "00:1A:79:AB:CD:EF");
    assert.equal(normalizeMac("001A79ABCDEF"), "00:1A:79:AB:CD:EF");
    assert.equal(normalizeMac("00:1A:79"), null);
    assert.equal(normalizePortal("portal.example:8080/c/"), "http://portal.example:8080");
    assert.equal(normalizePortal("http://portal.example/stalker_portal/c/index.html"), "http://portal.example/stalker_portal");
});

test("each kind of playlist is saved as the app saves it", () => {
    assert.deepEqual(
        buildPlaylist({ kind: "xtream", server: "a.example:8080/", username: " me ", password: "secret", name: "Den" }).playlist,
        {
            ...xtream,
            name: "Den",
        },
    );
    assert.deepEqual(buildPlaylist({ kind: "link", link: "https://b.example/list.m3u", guide: "https://b.example/epg.xml" }).playlist, {
        server: "",
        username: "",
        password: "",
        playlistUrl: "https://b.example/list.m3u",
        guideUrl: "https://b.example/epg.xml",
    });
    assert.deepEqual(buildPlaylist({ kind: "stalker", server: "portal.example/c/", mac: "001a79abcdef" }).playlist, {
        server: "http://portal.example",
        username: "00:1A:79:AB:CD:EF",
        password: "",
        playlistUrl: "stalker:",
    });
    // A pasted Xtream link in the link form signs in properly.
    assert.deepEqual(buildPlaylist({ kind: "link", link: "http://a.example:8080/get.php?username=me&password=secret" }).playlist, xtream);
});

test("the form says which field is wrong", () => {
    assert.deepEqual(buildPlaylist({ kind: "xtream", server: "", username: "me", password: "x" }), {
        error: "server needed",
        field: "server",
    });
    assert.deepEqual(buildPlaylist({ kind: "xtream", server: "a.example", username: "", password: "x" }), {
        error: "username needed",
        field: "username",
    });
    assert.deepEqual(buildPlaylist({ kind: "xtream", server: "a.example", username: "me" }), {
        error: "password needed",
        field: "password",
    });
    assert.deepEqual(buildPlaylist({ kind: "link", link: "what is this" }), { error: "bad link", field: "link" });
    assert.deepEqual(buildPlaylist({ kind: "stalker", server: "p.example", mac: "12" }), { error: "bad mac", field: "mac" });
    assert.deepEqual(buildPlaylist({ kind: "link", link: "http://b.example/x", guide: "javascript:alert(1)" }), {
        error: "bad guide",
        field: "guide",
    });
    assert.deepEqual(buildPlaylist({ kind: "other" }), { error: "bad request" });
});

test("editing keeps a password, link or guide left blank, and can drop the guide", () => {
    const saved = { ...xtream, guideUrl: "http://g.example/epg", extraGuides: ["http://h.example/x"], name: "Den" };
    assert.deepEqual(
        buildPlaylist({ kind: "xtream", server: saved.server, username: "me", password: "", name: "Den" }, saved).playlist,
        saved,
    );
    const { guideUrl, ...noGuide } = saved;
    assert.ok(guideUrl);
    assert.deepEqual(
        buildPlaylist({ kind: "xtream", server: saved.server, username: "me", clearGuide: true, name: "Den" }, saved).playlist,
        noGuide,
    );
    const link = { server: "", username: "", password: "", playlistUrl: "http://b.example/l.m3u?key=1" };
    assert.deepEqual(buildPlaylist({ kind: "link", link: "", name: "B" }, link).playlist, { ...link, name: "B" });
    // A password from another kind of playlist is never carried over.
    assert.equal(buildPlaylist({ kind: "xtream", server: "a.example", username: "me" }, link).error, "password needed");
});

test("the page never gets a password or link back", () => {
    const view = libraryView(
        stored([
            { ...xtream, guideUrl: "http://g.example/epg?key=k" },
            { server: "", username: "", password: "", playlistUrl: "http://b.example/l?pw=1" },
        ]),
    );
    const text = JSON.stringify(view);
    assert.ok(!text.includes("secret"));
    assert.ok(!text.includes("pw=1"));
    assert.ok(!text.includes("key=k"));
    assert.equal(view.playlists[0].detail, "me @ a.example:8080");
    assert.equal(view.playlists[0].guideHost, "g.example");
    assert.equal(view.playlists[1].kind, "link");
    assert.equal(view.playlists[1].detail, "b.example");
});

test("settings show their value or the app's default", () => {
    const view = libraryView(stored([xtream], { theme: { s: "DARK" }, live_grid: { b: true }, guide_days: { i: "bad" } }));
    const all = view.settings.flatMap((g) => g.settings);
    assert.equal(all.find((s) => s.key === "theme").value, "DARK");
    assert.equal(all.find((s) => s.key === "live_grid").value, true);
    assert.equal(all.find((s) => s.key === "guide_days").value, 3);
    assert.equal(libraryView(null).exists, false);
});

test("only the website's own settings and values can be set", () => {
    assert.deepEqual(encodeSetting("theme", "DARK"), { s: "DARK" });
    assert.deepEqual(encodeSetting("guide_days", 7), { i: 7 });
    assert.deepEqual(encodeSetting("live_grid", true), { b: true });
    assert.equal(encodeSetting("theme", "SEPIA"), null);
    assert.equal(encodeSetting("guide_days", 4), null);
    assert.equal(encodeSetting("live_grid", "yes"), null);
    assert.equal(encodeSetting("user_agent", "x"), null);
    assert.equal(encodeSetting(REFRESH_KEY, 1), null);
    for (const s of WEB_SETTINGS.flatMap((g) => g.settings)) assert.ok(s.label && "default" in s, s.key);
});

test("adding, editing and removing playlists changes the copy and marks it as changed", () => {
    const start = stored([xtream], { theme: { s: "LIGHT" } });
    start.copy.profiles = [{ id: 1 }];
    const added = applyChange(start, { op: "add", playlist: { kind: "link", link: "http://b.example/l.m3u" } }, 50).stored;
    assert.equal(added.copy.playlists.length, 2);
    assert.equal(added.changedAt, 50);
    assert.match(added.fingerprint, /^web-[0-9a-f]{24}$/);
    assert.deepEqual(added.copy.profiles, [{ id: 1 }]);
    assert.equal(start.copy.playlists.length, 1, "the stored copy isn't changed in place");

    assert.equal(
        applyChange(added, { op: "add", playlist: { kind: "xtream", ...xtream, server: "a.example:8080" } }).error,
        "already saved",
    );

    const id = playlistId(xtream);
    const edited = applyChange(added, {
        op: "edit",
        id,
        playlist: { kind: "xtream", server: xtream.server, username: "me", password: "new" },
    }).stored;
    assert.equal(edited.copy.playlists[0].password, "new");
    assert.equal(applyChange(added, { op: "edit", id: "nope", playlist: {} }).error, "gone");

    const removed = applyChange(edited, { op: "remove", id: playlistId(edited.copy.playlists[0]) }).stored;
    assert.deepEqual(
        removed.copy.playlists.map((p) => p.playlistUrl),
        ["http://b.example/l.m3u"],
    );
    assert.equal(applyChange(removed, { op: "remove", id: playlistId(removed.copy.playlists[0]) }).error, "last playlist");
});

test("an account without a backup gets a partial copy the app joins with its own", () => {
    const made = applyChange(null, { op: "add", playlist: { kind: "stalker", server: "p.example", mac: "001A79000001" } }, 9).stored;
    assert.equal(made.copy.partial, true);
    assert.equal(made.copy.version, 2);
    assert.equal(made.copy.playlists.length, 1);
});

test("settings and refresh go in the copy's settings", () => {
    const start = stored([xtream]);
    const set = applyChange(start, { op: "settings", values: { theme: "DARK", live_grid: true } }).stored;
    assert.deepEqual(set.copy.settings, { theme: { s: "DARK" }, live_grid: { b: true } });
    assert.equal(applyChange(start, { op: "settings", values: { theme: "SEPIA" } }).error, "bad setting");
    assert.equal(applyChange(start, { op: "settings", values: {} }).error, "bad request");
    const refreshed = applyChange(set, { op: "refresh" }, 1234).stored;
    assert.deepEqual(refreshed.copy.settings[REFRESH_KEY], { l: 1234 });
    assert.equal(libraryView(refreshed).refreshAskedAt, 1234);
    assert.equal(applyChange(start, { op: "explode" }).error, "bad request");
});

// The app's own test (WebSettingsTest) checks types and defaults; this one runs when only the website changed.
test("every website setting is a synced app setting", async () => {
    const { readFileSync } = await import("node:fs");
    const fields = readFileSync(new URL("../../data/src/main/kotlin/com/tunedplayer/app/data/SettingFields.kt", import.meta.url), "utf8");
    for (const s of WEB_SETTINGS.flatMap((g) => g.settings)) {
        const line = fields.split("\n").find((l) => l.includes(`("${s.key}",`));
        assert.ok(line, `${s.key} isn't in SETTING_FIELDS`);
        assert.ok(!line.includes("notBackedUp"), `${s.key} stays on each device`);
    }
    assert.ok(fields.includes(`long("${REFRESH_KEY}",`));
});

test("the provider check gets one saved playlist's login, and nothing a browser can't use", () => {
    const link = { server: "", username: "", password: "", playlistUrl: "http://b.example/list.m3u?key=k", name: "Mine" };
    Object.assign(link, { guideUrl: "http://g.example/epg.xml", extraGuides: ["http://g2.example/epg.xml"] });
    const portal = { server: "http://p.example", username: "00:1A:79:AB:CD:EF", password: "", playlistUrl: "stalker:" };
    const copy = stored([xtream, link, portal]);
    assert.deepEqual(checkLogin(copy, playlistId(xtream)), {
        kind: "xtream",
        name: "me @ a.example:8080",
        server: "http://a.example:8080",
        username: "me",
        password: "secret",
        guide: "",
        extraGuides: [],
    });
    assert.deepEqual(checkLogin(copy, playlistId(link)), {
        kind: "link",
        name: "Mine",
        link: "http://b.example/list.m3u?key=k",
        guide: "http://g.example/epg.xml",
        extraGuides: ["http://g2.example/epg.xml"],
    });
    assert.deepEqual(checkLogin(copy, playlistId(portal)), { error: "app only", kind: "stalker" });
    assert.deepEqual(checkLogin(copy, "0000000000000000"), { error: "gone" });
    assert.deepEqual(checkLogin(null, playlistId(xtream)), { error: "gone" });
});

test("a saved login is only handed to a fetch from our own page", () => {
    assert.equal(fromOurPage(new Headers({ "sec-fetch-site": "same-origin" })), true);
    for (const site of ["cross-site", "same-site", "none"]) assert.equal(fromOurPage(new Headers({ "sec-fetch-site": site })), false);
    assert.equal(fromOurPage(new Headers()), false);
});
