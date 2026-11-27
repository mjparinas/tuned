// What the TV keeps (docs/decisions/tizen.md): settings and logins as strings, and each playlist's channels, films,
// shows and guide, in IndexedDB. Opening the app reads from here at once; nothing waits on the network. A refresh
// writes a new generation beside the old one and switches only when it's complete, so the lists never empty mid-way.
// If IndexedDB fails (a full disk, a private window) everything still works from memory until the app closes.
import type { Item, KeyValueStore, Programme } from "@tuned/shared";
import { MemoryStore } from "@tuned/shared";

const NAME = "tuned";
const VERSION = 1;

type Stored = Item & { key: string; list: string; gen: number };
type StoredProgramme = Programme & { list: string };

let opening: Promise<IDBDatabase | null> | null = null;

function open(): Promise<IDBDatabase | null> {
    opening ??= new Promise((resolve) => {
        try {
            const request = indexedDB.open(NAME, VERSION);
            request.onupgradeneeded = () => {
                const db = request.result;
                db.createObjectStore("kv");
                const items = db.createObjectStore("items", { keyPath: "key" });
                items.createIndex("byKind", ["list", "gen", "kind"]);
                items.createIndex("byGen", ["list", "gen"]);
                const guide = db.createObjectStore("guide", { keyPath: ["list", "channel", "start"] });
                guide.createIndex("byList", "list");
            };
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => resolve(null);
            request.onblocked = () => resolve(null);
        } catch {
            resolve(null);
        }
    });
    return opening;
}

function done(tx: IDBTransaction): Promise<void> {
    return new Promise((resolve, reject) => {
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error ?? new Error("IndexedDB failed"));
        tx.onabort = () => reject(tx.error ?? new Error("IndexedDB aborted"));
    });
}

function result<T>(request: IDBRequest<T>): Promise<T> {
    return new Promise((resolve, reject) => {
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    });
}

const memory = { kv: new MemoryStore(), items: new Map<string, Stored>(), guide: new Map<string, StoredProgramme>() };

/** Strings by key, for tv/shared's account and the app's settings. */
export const kv: KeyValueStore = {
    async get(key) {
        const db = await open();
        if (!db) return memory.kv.get(key);
        try {
            const value = await result(db.transaction("kv").objectStore("kv").get(key));
            return typeof value === "string" ? value : null;
        } catch {
            return memory.kv.get(key);
        }
    },
    async set(key, value) {
        const db = await open();
        if (!db) return memory.kv.set(key, value);
        const tx = db.transaction("kv", "readwrite");
        tx.objectStore("kv").put(value, key);
        await done(tx).catch(() => memory.kv.set(key, value));
    },
    async remove(key) {
        const db = await open();
        if (!db) return memory.kv.remove(key);
        const tx = db.transaction("kv", "readwrite");
        tx.objectStore("kv").delete(key);
        await done(tx).catch(() => memory.kv.remove(key));
    },
};

export async function putItems(list: string, gen: number, items: Item[]): Promise<void> {
    const rows: Stored[] = items.map((i) => ({ ...i, key: `${list}|${gen}|${i.id}`, list, gen }));
    const db = await open();
    if (!db) {
        for (const r of rows) memory.items.set(r.key, r);
        return;
    }
    const tx = db.transaction("items", "readwrite");
    const store = tx.objectStore("items");
    for (const r of rows) store.put(r);
    await done(tx);
}

export async function readKind(list: string, gen: number, kind: Item["kind"]): Promise<Item[]> {
    const db = await open();
    let rows: Stored[];
    if (!db) rows = [...memory.items.values()].filter((r) => r.list === list && r.gen === gen && r.kind === kind);
    else
        rows = (await result(
            db
                .transaction("items")
                .objectStore("items")
                .index("byKind")
                .getAll(IDBKeyRange.only([list, gen, kind])),
        )) as Stored[];
    return rows.sort((a, b) => a.order - b.order);
}

/** Drops a playlist's generation (an old one after a refresh, or every one when the playlist is removed). */
export async function dropGeneration(list: string, gen: number): Promise<void> {
    const db = await open();
    if (!db) {
        for (const [k, r] of memory.items) if (r.list === list && r.gen === gen) memory.items.delete(k);
        return;
    }
    const tx = db.transaction("items", "readwrite");
    const index = tx.objectStore("items").index("byGen");
    const cursor = index.openKeyCursor(IDBKeyRange.only([list, gen]));
    cursor.onsuccess = () => {
        const c = cursor.result;
        if (!c) return;
        tx.objectStore("items").delete(c.primaryKey);
        c.continue();
    };
    await done(tx);
}

export async function putProgrammes(list: string, programmes: Programme[]): Promise<void> {
    const db = await open();
    if (!db) {
        for (const p of programmes) memory.guide.set(`${list}|${p.channel}|${p.start}`, { ...p, list });
        return;
    }
    const tx = db.transaction("guide", "readwrite");
    const store = tx.objectStore("guide");
    for (const p of programmes) store.put({ ...p, list });
    await done(tx);
}

/** A playlist's whole guide, by channel and start: read once into memory, since a TV's guide is a few days only. */
export async function readGuide(list: string): Promise<Programme[]> {
    const db = await open();
    if (!db) return [...memory.guide.values()].filter((p) => p.list === list);
    return (await result(db.transaction("guide").objectStore("guide").index("byList").getAll(IDBKeyRange.only(list)))) as Programme[];
}

export async function clearGuide(list: string): Promise<void> {
    const db = await open();
    if (!db) {
        for (const [k, p] of memory.guide) if (p.list === list) memory.guide.delete(k);
        return;
    }
    const tx = db.transaction("guide", "readwrite");
    const store = tx.objectStore("guide");
    const cursor = store.index("byList").openKeyCursor(IDBKeyRange.only(list));
    cursor.onsuccess = () => {
        const c = cursor.result;
        if (!c) return;
        store.delete(c.primaryKey);
        c.continue();
    };
    await done(tx);
}

/** Sign out: the app as it was installed (onboarding.md › Sign out). */
export async function clearEverything(): Promise<void> {
    memory.items.clear();
    memory.guide.clear();
    const db = await open();
    if (!db) return;
    const tx = db.transaction(["kv", "items", "guide"], "readwrite");
    for (const name of ["kv", "items", "guide"]) tx.objectStore(name).clear();
    await done(tx);
}
