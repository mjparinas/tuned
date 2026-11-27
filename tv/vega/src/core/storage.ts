// What the stick keeps, over an async string store (on the stick, Amazon's AsyncStorage in the app's private /data;
// in tests, a Map). Every key starts with "tuned:", so signing out clears all of it at once (AGENTS.md: a new store of
// data must be cleared on sign-out). The lineup is split into chunks so no single value gets large.
import type { Library, Lineup, Login, Title } from "./models";

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
    libraryHead: `${PREFIX}library`,
    libraryChunk: (part: "m" | "s", i: number) => `${PREFIX}library:${part}:${i}`,
    positions: `${PREFIX}positions`,
    /** Every playlist on the stick, and which one is open (`login` above is always the open one's). */
    playlists: `${PREFIX}playlists`,
    playlist: `${PREFIX}playlist`,
} as const;

/** A playlist kept on the stick. The first one's id is "current", so what it kept before there were several stays. */
export interface SavedPlaylist {
    id: string;
    login: Login;
}

/** How many movies and episodes keep where they were left; the oldest go first. */
const POSITIONS = 300;

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

        async loadLibrary(): Promise<Library | null> {
            type Head = Omit<Library, "movies" | "shows"> & { movieChunks: number; showChunks: number };
            const head = await readJson<Head>(store, KEYS.libraryHead);
            if (!head) return null;
            const read = async (part: "m" | "s", chunks: number) => {
                const out: Title[] = [];
                for (let i = 0; i < chunks; i++) {
                    const chunk = await readJson<Title[]>(store, KEYS.libraryChunk(part, i));
                    if (!chunk) return null;
                    out.push(...chunk);
                }
                return out;
            };
            const movies = await read("m", head.movieChunks);
            const shows = await read("s", head.showChunks);
            if (!movies || !shows) return null;
            return { movieCategories: head.movieCategories, showCategories: head.showCategories, movies, shows, savedAt: head.savedAt };
        },

        async saveLibrary(library: Library): Promise<void> {
            const write = async (part: "m" | "s", titles: Title[]) => {
                const chunks = Math.ceil(titles.length / CHUNK);
                for (let i = 0; i < chunks; i++) {
                    await store.set(KEYS.libraryChunk(part, i), JSON.stringify(titles.slice(i * CHUNK, (i + 1) * CHUNK)));
                }
                return chunks;
            };
            const movieChunks = await write("m", library.movies);
            const showChunks = await write("s", library.shows);
            await store.set(
                KEYS.libraryHead,
                JSON.stringify({
                    movieCategories: library.movieCategories,
                    showCategories: library.showCategories,
                    movieChunks,
                    showChunks,
                    savedAt: library.savedAt,
                }),
            );
            const stale = (await store.keys()).filter((k) => {
                const m = /^tuned:library:([ms]):(\d+)$/.exec(k);
                return !!m && Number(m[2]) >= (m[1] === "m" ? movieChunks : showChunks);
            });
            if (stale.length) await store.remove(stale);
        },

        /** Where each movie and episode was left, in seconds. */
        loadPositions: async () => (await readJson<Record<string, number>>(store, KEYS.positions)) ?? {},
        async savePosition(id: string, seconds: number): Promise<Record<string, number>> {
            const all = (await readJson<Record<string, number>>(store, KEYS.positions)) ?? {};
            delete all[id];
            if (seconds > 0) all[id] = Math.round(seconds);
            const keys = Object.keys(all);
            for (const old of keys.slice(0, Math.max(0, keys.length - POSITIONS))) delete all[old];
            await store.set(KEYS.positions, JSON.stringify(all));
            return all;
        },

        loadPlaylists: async () => (await readJson<SavedPlaylist[]>(store, KEYS.playlists)) ?? [],
        savePlaylists: (list: SavedPlaylist[]) => store.set(KEYS.playlists, JSON.stringify(list)),
        loadPlaylistId: () => store.get(KEYS.playlist),
        savePlaylistId: (id: string) => store.set(KEYS.playlist, id),

        /** Switching playlists: the open one's channels, movies and last channel go, so the next one's download replaces them. */
        async clearDownloads(): Promise<void> {
            const heads = [KEYS.lineupHead, KEYS.libraryHead];
            const gone = (await store.keys()).filter((k) => heads.some((h) => k === h || k.startsWith(`${h}:`)) || k === KEYS.lastChannel);
            if (gone.length) await store.remove(gone);
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
