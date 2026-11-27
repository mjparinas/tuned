// The profiles the TV reads from the account's backup, their PINs and kids mode (docs/decisions/profiles.md).
import assert from "node:assert/strict";
import { pbkdf2Sync } from "node:crypto";
import { test } from "node:test";
import { TunedAccount } from "./account.ts";
import type { Item } from "./catalog.ts";
import { MemoryStore } from "./platform.ts";
import {
    favouritesOnTv,
    PinThrottle,
    forKids,
    isKids,
    lockFor,
    parseWholeIds,
    profileGradient,
    profilesFromBackup,
    verifyPin,
    webPbkdf2,
    type Profile,
} from "./profiles.ts";
import { fakeFetch } from "./test-fetch.ts";

/** A PIN hashed as Android's `PinHash.create` does. */
const hashed = (pin: string) => {
    const salt = Buffer.from("0123456789abcdef");
    return `v1:${salt.toString("base64")}:${pbkdf2Sync(pin, salt, 120_000, 32, "sha256").toString("base64")}`;
};

// Android's ids are 64-bit, past what a JavaScript number holds exactly.
const big = "-7046029254386353131";
const backup = `{"playlists":[],"settings":{"adult_pin":{"s":"${hashed("4321")}"},"accent":{"s":"violet"}},
 "profiles":[
  {"id":2,"name":"Kids","colour":3,"kids":true,"createdAt":20,"avatar":"dragon",
   "favourites":[{"itemId":8070450532247928832,"addedAt":1,"position":1},{"itemId":${big},"addedAt":1,"position":0}]},
  {"id":1,"name":"Melvin \\"M\\" 1234567890123456789","colour":0,"kids":false,"pinHash":"${hashed("1234")}","createdAt":10}
 ]}`;

test("reads Android's 64-bit ids whole, and leaves strings and short numbers alone", () => {
    const parsed = parseWholeIds(`{"a":${big},"b":12,"c":"x:1234567890123456789","d":[1.5e300,12345678901234567]}`) as Record<
        string,
        unknown
    >;
    assert.equal(parsed.a, big);
    assert.equal(parsed.b, 12);
    assert.equal(parsed.c, "x:1234567890123456789");
    assert.deepEqual(parsed.d, [1.5e300, "12345678901234567"]);
});

test("profiles come from the backup in Android's order, with their favourites whole", () => {
    const { profiles, parentalPin } = profilesFromBackup(backup);
    assert.deepEqual(
        profiles.map((p) => p.name),
        ['Melvin "M" 1234567890123456789', "Kids"],
    );
    assert.deepEqual(profiles[1].favourites, [big, "8070450532247928832"]);
    assert.equal(profiles[1].kids, true);
    assert.equal(profiles[1].avatar, "dragon");
    assert.equal(profiles[0].pinHash?.startsWith("v1:"), true);
    assert.equal(parentalPin?.startsWith("v1:"), true);
    assert.deepEqual(profilesFromBackup("not json"), { profiles: [], parentalPin: null });
    assert.deepEqual(profilesFromBackup("{}"), { profiles: [], parentalPin: null });
});

test("PINs check against Android's hashes", async () => {
    const pbkdf2 = webPbkdf2(globalThis.crypto.subtle as never);
    assert.equal(await verifyPin(pbkdf2, "1234", hashed("1234")), true);
    assert.equal(await verifyPin(pbkdf2, "1235", hashed("1234")), false);
    assert.equal(await verifyPin(pbkdf2, "12", hashed("12")), false);
    assert.equal(await verifyPin(pbkdf2, "1234", "v2:x:y"), false);
    assert.equal(await verifyPin(pbkdf2, "1234", null), false);
});

test("asks for the PINs Android asks for", () => {
    const { profiles, parentalPin } = profilesFromBackup(backup);
    const [grownUp, kids] = profiles;
    assert.equal(lockFor(null, grownUp, parentalPin), "profile");
    assert.equal(lockFor(grownUp, kids, parentalPin), "none");
    const open: Profile = { ...grownUp, pinHash: null };
    assert.equal(lockFor(kids, open, parentalPin), "parental");
    assert.equal(lockFor(kids, open, null), "none");
    assert.equal(lockFor(kids, { ...kids, pinHash: null }, parentalPin), "none");
    assert.equal(lockFor(kids, grownUp, parentalPin), "profile");
});

const item = (over: Partial<Item>): Item => ({
    id: "l1",
    kind: "live",
    name: "",
    url: "",
    group: "",
    tvgId: null,
    logo: null,
    region: null,
    adult: false,
    showName: null,
    season: 0,
    episode: 0,
    order: 0,
    added: 0,
    year: null,
    rating: null,
    catchUpDays: 0,
    catchUp: null,
    number: 0,
    androidId: "0",
    ...over,
});

test("kids content and the PIN throttle follow core's ProfilesTest", () => {
    assert.equal(isKids("US| KIDS", "Anything", true), true);
    assert.equal(isKids("Family Movies", "Paddington", false), true);
    assert.equal(isKids("Animation", "Toy Story", false), true);
    assert.equal(isKids("US | Entertainment", "US: Cartoon Network HD", true), true);
    assert.equal(isKids("CA | General", "Disney Junior", true), true);
    assert.equal(isKids("US | Entertainment", "US: Family Guy 24/7", true), false);
    assert.equal(isKids("Comedy", "Nickel Boys", false), false);
    assert.equal(isKids("Sports", "TSN 1", true), false);
    let now = 0;
    const throttle = new PinThrottle(() => now);
    for (let i = 0; i < 4; i++) assert.equal(throttle.record(false), "wrong");
    assert.equal(throttle.record(false), "wait");
    assert.equal(throttle.waiting(), true);
    now = 29_999;
    assert.equal(throttle.waiting(), true);
    now = 30_000;
    assert.equal(throttle.waiting(), false);
    assert.equal(throttle.record(true), "ok");
});

test("a kids profile sees kids and family, and never adult", () => {
    assert.equal(forKids(item({ group: "US | Entertainment", name: "Cartoon Network" })), true);
    assert.equal(forKids(item({ group: "Family Movies", kind: "movie", name: "Up" })), true);
    assert.equal(forKids(item({ group: "Movies", kind: "movie", name: "Kids Gone Wild" })), false);
    assert.equal(forKids(item({ group: "Kids", adult: true })), false);
    assert.equal(forKids(item({ group: "News", name: "CBC" })), false);
});

test("a profile's favourites find this TV's items, and its circle takes Android's colours", () => {
    const kids = profilesFromBackup(backup).profiles[1];
    const items = [item({ id: "a", androidId: "8070450532247928832" }), item({ id: "b", androidId: big }), item({ id: "c" })];
    assert.deepEqual(favouritesOnTv(kids, items), ["b", "a"]);
    assert.deepEqual(profileGradient(kids), ["#34d399", "#065f46"]);
    assert.deepEqual(profileGradient({ ...kids, avatar: null, colour: 7 }), ["#7e61f3", "#3b229c"]);
});

test("the backup is read with the TV's own token", async () => {
    const store = new MemoryStore();
    await store.set("account.token", "t0k");
    const fetch = fakeFetch((url, init) =>
        url.pathname === "/api/backup" && init.headers?.Authorization === "Bearer t0k" ? { body: backup } : { status: 401, body: {} },
    );
    const account = new TunedAccount({
        fetch,
        store,
        crypto: globalThis.crypto as never,
        gunzip: async (b) => b,
        device: { kind: "tv", build: "tizen", name: "TV" },
        base: "https://x.test",
        log: () => undefined,
    });
    assert.equal(await account.backup(), backup);
    await store.remove("account.token");
    assert.equal(await account.backup(), null);
});
