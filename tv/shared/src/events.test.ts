// Live events and auto-tune's order, with core/EventsTest.kt's and core/ChannelEventsTest.kt's cases.
import assert from "node:assert/strict";
import { test } from "node:test";
import {
    ChannelHealth,
    english,
    eventsInNames,
    follows,
    followsFromBackup,
    groupEvents,
    hasWords,
    isEvent,
    liveAt,
    noLongerFollowed,
    parseChannelName,
    rankForTune,
    regionTag,
    ruleOffset,
    sportyChannel,
    tuneWords,
    withNamed,
    zoneOffset,
    type EventProgramme,
    type TuneCandidate,
} from "./events.ts";
import { MemoryStore } from "./platform.ts";

const hour = 3_600_000;

test("events are matches and named events; shows and replays aren't", () => {
    for (const t of [
        "Toronto Maple Leafs vs. Vancouver Canucks",
        "Arsenal v Chelsea",
        "Lakers @ Celtics",
        "UFC 312: Du Plessis vs. Strickland 2",
        "LIVE: Formula 1 Grand Prix of Las Vegas",
        "Premier League Darts Final",
    ]) {
        assert.equal(isEvent(t), true, t);
    }
    for (const t of ["SportsCenter", "NHL Tonight", "Arsenal v Chelsea Highlights", "UFC 312 Countdown", "Paid Programming", "Golf"]) {
        assert.equal(isEvent(t), false, t);
    }
    assert.equal(sportyChannel("US| ESPN HD", "US| Entertainment"), true);
    assert.equal(sportyChannel("PPV 7", "US| PPV"), true);
    assert.equal(sportyChannel("Sky Sports Main Event", "UK"), true);
    assert.equal(sportyChannel("CNN", "US| News"), false);
});

test("the same event on several channels is one", () => {
    const row = (channelId: string, title: string, start: number, stop: number): EventProgramme => ({ channelId, title, start, stop });
    const rows = [
        row("1", "LIVE: Leafs vs. Canucks", 10 * hour, 13 * hour),
        row("2", "Leafs vs. Canucks (L)", 10 * hour + 5 * 60_000, 13 * hour),
        row("3", "Leafs vs. Canucks", 22 * hour, 25 * hour),
        row("4", "UFC 312: Main Card", 9 * hour, 11 * hour),
        row("5", "Bills vs. Jets", 6 * hour, 9 * hour),
        row("6", "SportsCenter", 13 * hour, 14 * hour),
    ];
    const now = 10 * hour + 30 * 60_000;
    const events = groupEvents(rows, now);
    assert.deepEqual(
        events.map((e) => e.title),
        ["UFC 312: Main Card", "Leafs vs. Canucks", "Leafs vs. Canucks"],
    );
    assert.deepEqual(events[1].channels, ["1", "2"]);
    assert.equal(liveAt(events[0], now), true);
});

test("followed names match whole words, and unfollowing keeps what another name covers", () => {
    const [e] = groupEvents([{ channelId: "1", title: "Leafs vs. Canucks", start: 0, stop: hour }], 0);
    assert.equal(follows(e, ["canucks"]), true);
    assert.equal(follows(e, ["Leafs"]), true);
    assert.equal(follows(e, ["Can"]), false);
    assert.equal(follows(e, [""]), false);
    const events = groupEvents(
        [
            { channelId: "1", title: "NHL: Canucks vs. Oilers", start: 0, stop: hour },
            { channelId: "2", title: "Canucks vs. Flames", start: 0, stop: hour },
            { channelId: "3", title: "Leafs vs. Habs", start: 0, stop: hour },
        ],
        0,
    );
    assert.deepEqual(
        noLongerFollowed(events, "Canucks", ["NHL"]).map((x) => x.title),
        ["Canucks vs. Flames"],
    );
    assert.deepEqual(noLongerFollowed(events, "Raptors", ["NHL"]), []);
});

test("follows come from the backup as Android keeps it", () => {
    const copy = { settings: { followed_events: { set: ["Canucks", "UFC"] }, other: { b: true } } };
    assert.deepEqual(followsFromBackup(JSON.stringify({ fingerprint: "x", changedAt: 1, copy })), ["Canucks", "UFC"]);
    assert.deepEqual(followsFromBackup(JSON.stringify(copy)), ["Canucks", "UFC"]);
    assert.deepEqual(followsFromBackup("{}"), []);
    assert.deepEqual(followsFromBackup("not json"), []);
    assert.deepEqual(followsFromBackup(null), []);
});

// Wednesday 25 November 2026, noon in Toronto (UTC-5).
const toronto = "America/Toronto";
const at = (y: number, mo: number, d: number, h: number, mi: number, offsetHours: number) =>
    Date.UTC(y, mo - 1, d, h, mi) - offsetHours * hour;
const now = at(2026, 11, 25, 12, 0, -5);
const parse = (name: string, zone: string = toronto) => parseChannelName(name, now, zone);

test("a slot's label and time come off its name", () => {
    assert.deepEqual(parse("PPV 7: UFC 312 10PM ET"), { title: "UFC 312", start: at(2026, 11, 25, 22, 0, -5) });
    assert.deepEqual(parse("US| NHL 05: Leafs vs Canucks @ 7:00 PM EST"), {
        title: "Leafs vs Canucks",
        start: at(2026, 11, 25, 19, 0, -5),
    });
    assert.deepEqual(parse("UK: EPL 01 | Arsenal v Chelsea 20:00 GMT"), { title: "Arsenal v Chelsea", start: at(2026, 11, 25, 20, 0, 0) });
});

test("a date and a weekday are read", () => {
    assert.deepEqual(parse("DAZN PPV 1: Canelo vs Crawford | Sat 9PM ET"), {
        title: "Canelo vs Crawford",
        start: at(2026, 11, 28, 21, 0, -5),
    });
    assert.deepEqual(parse("ESPN+ 12 : Duke @ UNC 11/26 07:00PM"), { title: "Duke @ UNC", start: at(2026, 11, 26, 19, 0, -5) });
    assert.deepEqual(parse("NBA 03 - Lakers vs Celtics (2026-11-25 19:30)"), {
        title: "Lakers vs Celtics",
        start: at(2026, 11, 25, 19, 30, -5),
    });
    assert.deepEqual(parse("LALIGA 2: Real Madrid vs Barcelona Nov 26 3pm ET"), {
        title: "Real Madrid vs Barcelona",
        start: at(2026, 11, 26, 15, 0, -5),
    });
});

test("a time with no zone is in the TV's zone, and one with no date is the next not over", () => {
    assert.equal(parse("PPV 7: UFC 312 10PM", "America/Los_Angeles")?.start, at(2026, 11, 25, 22, 0, -8));
    assert.deepEqual(parse("PPV 7: UFC 312 10PM ET / 7PM PT"), { title: "UFC 312", start: at(2026, 11, 25, 22, 0, -5) });
    assert.equal(parse("PPV 2: UFC Fight Night 1AM ET")?.start, at(2026, 11, 26, 1, 0, -5));
    assert.equal(parse("PPV 2: UFC Fight Night 10AM ET")?.start, at(2026, 11, 25, 10, 0, -5));
    assert.equal(parse("PPV 2: UFC Fight Night 12:30AM ET")?.start, at(2026, 11, 26, 0, 30, -5));
});

test("untimed events have no start; empty slots and plain channels are no event", () => {
    assert.deepEqual(parse("NHL 05: Leafs vs Canucks"), { title: "Leafs vs Canucks", start: null });
    assert.deepEqual(parse("PPV 7: UFC 312 (Feb 8)"), { title: "UFC 312", start: null });
    assert.deepEqual(parse("Lakers @ Celtics"), { title: "Lakers @ Celtics", start: null });
    assert.deepEqual(parse("WNBA 2: Bobcats vs Sun"), { title: "Bobcats vs Sun", start: null });
    assert.deepEqual(parse("PPV 9: Mayweather vs Pacquiao 2"), { title: "Mayweather vs Pacquiao 2", start: null });
    for (const name of [
        "PPV 7",
        "US| NHL 05",
        "NFL 01 -- No Event Streaming --",
        "PPV 3: Off Air",
        "US| ESPN HD",
        "Sky Sports Main Event",
        "NFL Sunday Ticket 3",
        "UK: Sky Sports Golf Open",
        "EPL 04: Arsenal v Chelsea Highlights 8PM",
    ]) {
        assert.equal(parse(name), null, name);
    }
});

test("names skip ended, far-off and impossible events, and join the guide's", () => {
    const found = eventsInNames(
        [
            { id: "1", name: "PPV 1: UFC 312 10PM ET" },
            { id: "2", name: "PPV 2: UFC 300 11/25 6AM ET" },
            { id: "3", name: "PPV 3: Boxing Final 12/25 9PM ET" },
            { id: "4", name: "PPV 4" },
            { id: "5", name: "PPV 5: Canelo vs Crawford 2/30 9PM ET" },
        ],
        now,
        now + 36 * hour,
        toronto,
    );
    assert.deepEqual(
        found.map((f) => f.channelId),
        ["1"],
    );
    const start = at(2026, 11, 25, 22, 0, -5);
    const guide = [{ key: "ufc 312", title: "UFC 312", start, stop: start + 3 * hour, channels: ["10"], timeKnown: true }];
    const merged = withNamed(
        guide,
        [
            { channelId: "1", event: { title: "UFC 312", start: start + 15 * 60_000 } },
            { channelId: "2", event: { title: "UFC 312", start: null } },
            { channelId: "3", event: { title: "Leafs vs Canucks", start: null } },
        ],
        now,
    );
    assert.equal(merged.length, 2);
    assert.deepEqual(merged.find((e) => e.key === "ufc 312")?.channels, ["10", "1", "2"]);
    const untimed = merged.find((e) => e.key === "leafs vs canucks")!;
    assert.equal(untimed.timeKnown, false);
    assert.equal(liveAt(untimed, now), false);
});

test("the zone rules agree with the engine's time zones", () => {
    const zones = ["America/New_York", "America/Los_Angeles", "America/Halifax", "Europe/London", "Europe/Paris", "Australia/Sydney"];
    for (const zone of zones) {
        for (let t = Date.UTC(2026, 0, 1); t < Date.UTC(2027, 0, 1); t += 6 * hour + 17 * 60_000) {
            assert.equal(ruleOffset(zone, t), zoneOffset(zone, t), `${zone} ${new Date(t).toISOString()}`);
        }
    }
});

test("auto-tune tries English, known-good, guide-listed and sharper channels first, and failed ones last", () => {
    const c = (id: string, name: string, extra: Partial<TuneCandidate> = {}): TuneCandidate => ({
        id,
        name,
        group: "PPV",
        region: regionTag(name, "PPV"),
        nameMatch: true,
        guideMatch: false,
        ...extra,
    });
    const t = 100 * 24 * hour;
    const ranked = rankForTune(
        [
            c("es", "ES| PPV 1"),
            c("dead", "US| PPV 2", { deadAt: t - hour }),
            c("sd", "US| PPV 3 SD"),
            c("fhd", "US| PPV 4 FHD"),
            c("uk", "UK| PPV 5 FHD"),
            c("ok", "US| PPV 6", { okAt: t - 24 * hour }),
            c("dub", "US| PPV 7 Spanish"),
        ],
        t,
    );
    assert.deepEqual(
        ranked.map((x) => x.id),
        ["ok", "fhd", "sd", "uk", "es", "dub", "dead"],
    );
    assert.equal(english({ name: "PPV", group: "", region: null }), 0);
    assert.equal(regionTag("CA: Sportsnet", ""), "CA");
    assert.equal(regionTag("PPV 7: UFC", "US| PPV"), "US");
    assert.equal(regionTag("ESPN", "Sports"), null);
    assert.deepEqual(tuneWords("UFC 335"), ["ufc", "335"]);
    assert.deepEqual(tuneWords("ufc335"), ["ufc", "335"]);
    assert.equal(hasWords("PPV 2: 335 UFC", tuneWords("UFC 335")), true);
    assert.equal(hasWords("UFC335 Main Card", tuneWords("UFC 335")), true);
    assert.equal(hasWords("UFC 334", tuneWords("UFC 335")), false);
});

test("channel health is kept per playlist", async () => {
    const store = new MemoryStore();
    const health = new ChannelHealth(store, "health.current");
    await health.played("a", 10);
    await health.failed("b", 20);
    const again = new ChannelHealth(store, "health.current");
    await again.load();
    assert.deepEqual(again.of("a"), { okAt: 10, deadAt: 0 });
    assert.deepEqual(again.of("b"), { okAt: 0, deadAt: 20 });
    assert.deepEqual(again.of("c"), { okAt: 0, deadAt: 0 });
});
