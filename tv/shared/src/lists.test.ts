// Lists, Favourites and channel edits read from and written to the account's backup (docs/decisions/lists.md).
import assert from "node:assert/strict";
import { test } from "node:test";
import {
    applyListChange,
    changeBackup,
    idText,
    listOnTv,
    listsFromBackup,
    parseKeepingIds,
    randomListId,
    stringifyKeepingIds,
    withEdit,
    type BackupAccess,
} from "./lists.ts";
import { profilesFromBackup } from "./profiles.ts";

const big = "-7046029254386353131";
const other = "8070450532247928832";
const copy = `{"version":2,"playlists":[{"name":"Home","n":1.5}],"settings":{"accent":{"s":"violet"}},
 "channelEdits":[{"itemId":${big},"name":"My News","logo":null},{"itemId":12,"name":null,"logo":"javascript:x"}],
 "profiles":[{"id":1,"name":"Melvin","colour":0,"kids":false,"createdAt":10,
   "favourites":[{"itemId":${other},"addedAt":1,"position":1}],
   "lists":[{"id":7,"name":"Sport","position":2,"createdAt":5,"items":[{"itemId":${other},"addedAt":9,"position":2},{"itemId":${big},"addedAt":3,"position":1}]},
            {"id":4,"name":"Films","position":1,"createdAt":4,"items":[]}]}]}`;
const backup = `{"fingerprint":"f1","changedAt":1795000000000,"copy":${copy}}`;

test("the backup goes back as it came, every 64-bit id the same number", () => {
    const opened = parseKeepingIds(backup);
    assert.equal(stringifyKeepingIds(opened), backup.replace(/\n\s*/g, ""));
    assert.equal(stringifyKeepingIds(opened).includes(big), true);
    assert.equal(stringifyKeepingIds(parseKeepingIds(`{"s":"\\u0001x ${big}","n":${big}}`)), `{"s":"\\u0001x ${big}","n":${big}}`);
    assert.equal(idText(7), "7");
    assert.equal(idText(2 ** 60), null);
});

test("a profile's lists in the phone's order, channels in the order they were added, and the account's edits", () => {
    const { lists, edits } = listsFromBackup(backup);
    assert.deepEqual(
        lists["1"].map((l) => l.name),
        ["Films", "Sport"],
    );
    assert.deepEqual(lists["1"][1].items, [big, other]);
    assert.deepEqual(edits, { [big]: { name: "My News", logo: null } });
    assert.deepEqual(listsFromBackup("not json"), { lists: {}, edits: {} });
    const items = [
        { id: "a", androidId: other, kind: "live" as const, name: "A", logo: null },
        { id: "b", androidId: big, kind: "live" as const, name: "News", logo: "http://x/l.png" },
    ];
    assert.deepEqual(
        listOnTv(lists["1"][1], items).map((i) => i.id),
        ["b", "a"],
    );
    assert.equal(withEdit(items[1], edits).name, "My News");
    assert.equal(withEdit(items[1], edits).logo, "http://x/l.png");
    assert.equal(withEdit(items[0], edits), items[0]);
});

test("changes are made as the website makes them", () => {
    const opened = parseKeepingIds(backup) as { copy: Record<string, unknown> };
    const change = (c: Parameters<typeof applyListChange>[1]) => applyListChange(opened.copy, c, 100, () => "99");
    assert.equal(change({ op: "favourite", profileId: "1", itemId: big, on: true }), null);
    assert.equal(change({ op: "favourite", profileId: "1", itemId: other, on: false }), null);
    assert.equal(change({ op: "list_add", profileId: "1", name: "  Kids night  ", itemId: big }), null);
    assert.equal(change({ op: "list_item", profileId: "1", listId: "4", itemId: other, on: true }), null);
    assert.equal(change({ op: "list_delete", profileId: "1", listId: "7" }), null);
    assert.equal(change({ op: "list_delete", profileId: "1", listId: "7" }), "gone");
    assert.equal(change({ op: "list_add", profileId: "1", name: "   " }), "bad name");
    assert.equal(change({ op: "favourite", profileId: "5", itemId: big, on: true }), "gone");
    const text = stringifyKeepingIds(opened);
    assert.deepEqual(profilesFromBackup(text).profiles[0].favourites, [big]);
    const { lists } = listsFromBackup(text);
    assert.deepEqual(
        lists["1"].map((l) => [l.id, l.name, l.items]),
        [
            ["4", "Films", [other]],
            ["99", "Kids night", [big]],
        ],
    );
});

test("a change is saved on top of the version read, and made again when another device saved first", async () => {
    let stored = backup;
    let version = 3;
    const saves: number[] = [];
    const account: BackupAccess = {
        backupToChange: async () => ({ text: stored, version }),
        saveBackup: async (text, base) => {
            saves.push(base);
            if (saves.length === 1) {
                // The phone saved in between: its copy has a new favourite.
                version = 4;
                stored = stored.replace(`"favourites":[`, `"favourites":[{"itemId":5,"addedAt":2,"position":2},`);
                return "conflict";
            }
            stored = text;
            version = base + 1;
            return "ok";
        },
    };
    const random = (b: Uint8Array) => b.fill(1);
    const done = await changeBackup(account, { op: "favourite", profileId: "1", itemId: big, on: true }, { now: () => 50, random });
    assert.ok("text" in done);
    assert.deepEqual(saves, [3, 4]);
    assert.deepEqual(profilesFromBackup(stored).profiles[0].favourites, [other, "5", big]);
    const whole = JSON.parse(stored.replace(/(?<=[:\[,])-?\d{16,}/g, "0")) as Record<string, unknown>;
    assert.equal(whole.fingerprint, "tv-010101010101010101010101");
    assert.equal(whole.changedAt, 50);
    assert.equal(stored.includes(big), true);

    const offline: BackupAccess = { backupToChange: async () => null, saveBackup: async () => "ok" };
    assert.deepEqual(await changeBackup(offline, { op: "list_delete", profileId: "1", listId: "4" }, { random }), { problem: "offline" });
    const busy: BackupAccess = { backupToChange: async () => ({ text: backup, version: 1 }), saveBackup: async () => "conflict" };
    assert.deepEqual(await changeBackup(busy, { op: "list_delete", profileId: "1", listId: "4" }, { random }), { problem: "busy" });
    assert.equal(randomListId(random), String((0x01010101010101 % (Number.MAX_SAFE_INTEGER - 1)) + 1));
});

test("the TV reads the backup with its version and saves on top of it, as the app's upload does", async () => {
    const { TunedAccount } = await import("./account.ts");
    const { MemoryStore } = await import("./platform.ts");
    const { fakeFetch } = await import("./test-fetch.ts");
    const store = new MemoryStore();
    await store.set("account.token", "t0k");
    let version = 6;
    const fetch = fakeFetch((url, init) => {
        if (url.pathname !== "/api/backup" || init.headers?.Authorization !== "Bearer t0k") return { status: 401, body: {} };
        if (init.method !== "PUT") return { body: backup, headers: { "X-Backup-Version": String(version) } };
        if (init.headers["If-Match"] !== `"${version}"`) return { status: 409, body: { status: "conflict" } };
        version++;
        return { body: { status: "ok", version } };
    });
    const account = new TunedAccount({
        fetch,
        store,
        crypto: globalThis.crypto as never,
        gunzip: async (b) => b,
        device: { kind: "tv", build: "tizen", name: "TV" },
        base: "https://x.test",
        log: () => undefined,
    });
    assert.deepEqual(await account.backupToChange(), { text: backup, version: 6 });
    assert.equal(await account.saveBackup(backup, 5), "conflict");
    assert.equal(await account.saveBackup(backup, 6), "ok");
    await store.remove("account.token");
    assert.equal(await account.backupToChange(), null);
});
