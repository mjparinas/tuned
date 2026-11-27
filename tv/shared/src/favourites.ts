// Favourites for the TV apps: the ids a person starred, kept per playlist in the app's own store, so switching
// playlists shows each one's own (as Favourites on Android, docs/decisions/lists.md). `@tuned/shared/favourites`.
import type { KeyValueStore } from "./platform.ts";

export class Favourites {
    private ids = new Set<string>();
    private readonly listeners = new Set<(ids: ReadonlySet<string>) => void>();

    private readonly store: KeyValueStore;
    private readonly key: string;

    /** `key` is where they're kept, such as "favourites.<playlist id>". */
    constructor(store: KeyValueStore, key: string) {
        this.store = store;
        this.key = key;
    }

    async load(): Promise<ReadonlySet<string>> {
        try {
            const raw = await this.store.get(this.key);
            const list: unknown = raw ? JSON.parse(raw) : [];
            this.ids = new Set(Array.isArray(list) ? list.filter((x): x is string => typeof x === "string") : []);
        } catch {
            this.ids = new Set();
        }
        this.tell();
        return this.ids;
    }

    has(id: string): boolean {
        return this.ids.has(id);
    }

    get all(): ReadonlySet<string> {
        return this.ids;
    }

    /** Stars or unstars `id`; true when it's now a favourite. The screen changes at once and the store follows. */
    async toggle(id: string): Promise<boolean> {
        const next = new Set(this.ids);
        const on = !next.has(id);
        if (on) next.add(id);
        else next.delete(id);
        this.ids = next;
        this.tell();
        await this.store.set(this.key, JSON.stringify([...next]));
        return on;
    }

    onChange(listener: (ids: ReadonlySet<string>) => void): () => void {
        this.listeners.add(listener);
        return () => void this.listeners.delete(listener);
    }

    private tell(): void {
        for (const l of this.listeners) {
            try {
                l(this.ids);
            } catch {
                // A screen's listener failing must not stop the others.
            }
        }
    }
}

/** Where a playlist's favourites are kept, the same key the Samsung app has used from the start. */
export const favouritesKey = (playlistId: string) => `favourites.${playlistId}`;
