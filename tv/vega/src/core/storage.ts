// What the stick keeps, over an async string store (on the stick, Amazon's AsyncStorage in the app's private /data;
// in tests, a Map). Every key starts with "tuned:", so signing out clears all of it at once (AGENTS.md: a new store of
// data must be cleared on sign-out). The lineup is split into chunks so no single value gets large.
import type { Lineup, Login } from "./models";

export interface Store {
    get(key: string): Promise<string | null>;
    set(key: string, value: string): Promise<void>;
    remove(keys: string[]): Promise<void>;
    keys(): Promise<string[]>;
}

export const PREFIX = "tuned:";
const CHUNK = 2000;

export const KEYS = {
    login: `${PREFIX}login`,
    lineupHead: `${PREFIX}lineup`,
    lineupChunk: (i: number) => `${PREFIX}lineup:${i}`,
    lastChannel: `${PREFIX}lastChannel`,
} as const;

async function readJson<T>(store: Store, key: string): Promise<T | null> {
    const raw = await store.get(key);
    if (!raw) return null;
    try {
        return JSON.parse(raw) as T;
    } catch {
        return null;
    }
}

export function storage(store: Store) {
    return {
        loadLogin: () => readJson<Login>(store, KEYS.login),
        saveLogin: (login: Login) => store.set(KEYS.login, JSON.stringify(login)),

        async loadLineup(): Promise<Lineup | null> {
            const head = await readJson<{ categories: Lineup["categories"]; chunks: number; savedAt: number }>(store, KEYS.lineupHead);
            if (!head) return null;
            const channels: Lineup["channels"] = [];
            for (let i = 0; i < head.chunks; i++) {
                const chunk = await readJson<Lineup["channels"]>(store, KEYS.lineupChunk(i));
                if (!chunk) return null;
                channels.push(...chunk);
            }
            return { categories: head.categories, channels, savedAt: head.savedAt };
        },

        async saveLineup(lineup: Lineup): Promise<void> {
            const chunks = Math.ceil(lineup.channels.length / CHUNK);
            for (let i = 0; i < chunks; i++) {
                await store.set(KEYS.lineupChunk(i), JSON.stringify(lineup.channels.slice(i * CHUNK, (i + 1) * CHUNK)));
            }
            // The head goes last, so a stick that stops halfway keeps reading the old lineup's head and chunks.
            await store.set(KEYS.lineupHead, JSON.stringify({ categories: lineup.categories, chunks, savedAt: lineup.savedAt }));
            const stale = (await store.keys()).filter((k) => k.startsWith(`${KEYS.lineupHead}:`) && Number(k.split(":").pop()) >= chunks);
            if (stale.length) await store.remove(stale);
        },

        loadLastChannel: () => store.get(KEYS.lastChannel),
        saveLastChannel: (id: string) => store.set(KEYS.lastChannel, id),

        /** Back to how it was installed: everything Tuned kept. */
        async reset(): Promise<void> {
            const mine = (await store.keys()).filter((k) => k.startsWith(PREFIX));
            if (mine.length) await store.remove(mine);
        },
    };
}

export type Storage = ReturnType<typeof storage>;

/** A store in memory, for tests and the browser preview. */
export function memoryStore(seed: Record<string, string> = {}): Store {
    const map = new Map(Object.entries(seed));
    return {
        get: async (k) => map.get(k) ?? null,
        set: async (k, v) => void map.set(k, v),
        remove: async (keys) => keys.forEach((k) => map.delete(k)),
        keys: async () => [...map.keys()],
    };
}
