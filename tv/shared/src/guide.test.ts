// The same cases as core's GuideGridTest and M3uCatchUpTest, so the TV apps build the links Android builds.
import assert from "node:assert/strict";
import { test } from "node:test";
import {
    HOUR,
    MINUTE,
    catchUpLink,
    clampStart,
    decodeCatchUp,
    earliest,
    keepFrom,
    latest,
    overlapping,
    partsIn,
    replayable,
    reveal,
    slotStart,
    ticks,
    xtreamDays,
    type CatchUp,
} from "./guide.ts";

const noon = Date.UTC(2026, 10, 17, 12);
const DAY = 24 * HOUR;

test("ticks fall on the half hour", () => {
    assert.equal(slotStart(noon + 29 * MINUTE), noon);
    assert.equal(slotStart(noon + 30 * MINUTE), noon + 30 * MINUTE);
    assert.deepEqual(ticks(noon + 10 * MINUTE, noon + HOUR), [noon, noon + 30 * MINUTE, noon + HOUR]);
    assert.deepEqual(ticks(noon, noon - 1), []);
});

test("scrolls back an hour or as far as catch-up goes, and ahead as far as the guide", () => {
    assert.equal(earliest(noon + 5 * MINUTE, 0), noon - HOUR);
    assert.equal(earliest(noon + 5 * MINUTE, 2), noon - 2 * DAY);
    assert.equal(earliest(noon, 30), noon - 7 * DAY);
    assert.equal(latest(noon + 5 * MINUTE, 3), noon + 3 * DAY);
    assert.equal(latest(noon, 0), noon + DAY);
});

test("clamps the view inside the guide", () => {
    const first = noon - HOUR;
    const last = noon + DAY;
    assert.equal(clampStart(noon - 5 * HOUR, first, last, 2 * HOUR), first);
    assert.equal(clampStart(last, first, last, 2 * HOUR), last - 2 * HOUR);
    assert.equal(clampStart(noon, first, last, 2 * HOUR), noon);
    assert.equal(clampStart(noon, first, last, 48 * HOUR), first);
});

test("keeps only programmes on in the span, in order", () => {
    const all = [
        { start: noon + HOUR, stop: noon + 2 * HOUR },
        { start: noon - 30 * MINUTE, stop: noon + 30 * MINUTE },
        { start: noon - 2 * HOUR, stop: noon - HOUR },
    ];
    assert.deepEqual(overlapping(all, noon, noon + HOUR), [all[1]]);
});

test("reveals a programme off either edge, and leaves time alone for one still on", () => {
    const view = noon;
    const visible = 2 * HOUR;
    assert.equal(reveal(noon + 30 * MINUTE, noon + 90 * MINUTE, view, visible), noon);
    assert.equal(reveal(noon - 50 * MINUTE, noon + 10 * MINUTE, view, visible), noon);
    assert.equal(reveal(noon - 50 * MINUTE, noon - 10 * MINUTE, view, visible), noon - HOUR);
    assert.equal(reveal(noon + 2 * HOUR, noon + 3 * HOUR, view, visible), noon + HOUR);
});

test("catch-up days come from Xtream's tv_archive, and from the playlist's encoded catch-up", () => {
    assert.equal(xtreamDays("1", "3"), 3);
    assert.equal(xtreamDays(1, 30), 7);
    assert.equal(xtreamDays("0", "3"), 0);
    assert.equal(xtreamDays(undefined, undefined), 0);
    assert.deepEqual(decodeCatchUp("default\nhttp://h/{utc}", 3), { mode: "default", days: 3, source: "http://h/{utc}" });
    assert.deepEqual(decodeCatchUp("shift\n", 0), { mode: "shift", days: 5, source: null });
    assert.equal(decodeCatchUp("nonsense\n", 3), null);
    assert.equal(decodeCatchUp(null, 3), null);
});

test("a programme replays only once it has started and while the server keeps it", () => {
    assert.equal(replayable(noon - HOUR, noon, 3), true);
    assert.equal(replayable(noon + HOUR, noon, 3), false);
    assert.equal(replayable(noon - 4 * DAY, noon, 3), false);
    assert.equal(replayable(noon - HOUR, noon, 0), false);
    assert.equal(keepFrom(noon, 2), noon - 2 * DAY);
});

// 2026-11-17 20:00 to 21:00 UTC, watched at 22:30, as in M3uCatchUpTest.
const start = 1_794_945_600_000;
const stop = start + HOUR;
const now = start + 9_000_000;
const s = start / 1000;
const link = (live: string, c: Partial<CatchUp> & Pick<CatchUp, "mode">) =>
    catchUpLink(live, { days: 3, source: null, ...c }, start, stop, now, "UTC");

test("fills Kodi's and TiviMate's placeholders", () => {
    assert.equal(
        link("http://h/live", {
            mode: "default",
            source: "http://h/a?s={utc}&e={utcend}&n={lutc}&d={duration}&m={duration:60}&o={offset}&date={Y}-{m}-{d}:{H}-{M}",
        }),
        `http://h/a?s=${s}&e=${s + 3600}&n=${now / 1000}&d=3600&m=60&o=9000&date=2026-11-17:20-00`,
    );
    assert.equal(
        link("http://h/live", { mode: "default", source: "http://h/${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}" }),
        "http://h/20261117200000-20261117210000",
    );
    assert.equal(link("http://h/live", { mode: "default", source: "http://h/${start}/{unknown}" }), `http://h/${s}/{unknown}`);
    assert.equal(link("http://h/live", { mode: "default", source: "http://h/{utc:Y-m-d}" }), "http://h/2026-11-17");
});

test("builds each mode's link", () => {
    assert.equal(link("http://h/a.ts", { mode: "shift" }), `http://h/a.ts?utc=${s}&lutc=${now / 1000}`);
    assert.equal(link("http://h/a.ts?t=1", { mode: "shift" }), `http://h/a.ts?t=1&utc=${s}&lutc=${now / 1000}`);
    assert.equal(link("http://h/a.ts", { mode: "append", source: "?start={utc}" }), `http://h/a.ts?start=${s}`);
    assert.equal(link("http://h/ch/index.m3u8?token=x", { mode: "flussonic" }), `http://h/ch/index-${s}-3600.m3u8?token=x`);
    assert.equal(link("http://h/ch/mpegts", { mode: "flussonic" }), `http://h/ch/timeshift_abs-${s}.ts`);
    assert.equal(link("http://h:8080/live/u/p/42.ts", { mode: "xc" }), "http://h:8080/timeshift/u/p/60/2026-11-17:20-00/42.ts");
    assert.equal(link("http://h:8080/u/p/42.m3u8", { mode: "xc" }), "http://h:8080/timeshift/u/p/60/2026-11-17:20-00/42.m3u8");
    assert.equal(link("http://h/a.ts", { mode: "flussonic" }), null);
    assert.equal(link("http://h/a.ts", { mode: "default" }), null);
});

test("keeps the live link's headers, and reads dates in the server's time zone", () => {
    assert.equal(link("http://h/a.ts|User-Agent=Box", { mode: "shift" }), `http://h/a.ts?utc=${s}&lutc=${now / 1000}|User-Agent=Box`);
    // 20:00 UTC is 15:00 in Toronto in November.
    assert.equal(
        catchUpLink("http://h/live/u/p/42.ts", { mode: "xc", days: 3, source: null }, start, stop, now, "America/Toronto"),
        "http://h/timeshift/u/p/60/2026-11-17:15-00/42.ts",
    );
    assert.deepEqual(partsIn(start, "Not/AZone"), partsIn(start, null));
});
