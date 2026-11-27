// Where each movie and episode was left, and Continue Watching: the newest unfinished one per movie or show, as the
// row on Android's Home. Kept in the app's own store; one near its end counts as watched and leaves the row.
// `@tuned/shared/progress`.
import type { KeyValueStore } from "./platform.ts";

export interface Watched {
    /** The movie's or episode's id. */
    id: string;
    /** The movie, or the show the episode is in. */
    titleId: string;
    /** Seconds in. */
    at: number;
    /** Seconds long; 0 when the player didn't know. */
    length: number;
    /** When, in ms. */
    watchedAt: number;
}

/** How much has to be watched for it to count as done. */
export const DONE = 0.95;
/** Under this many seconds in, it isn't worth resuming. */
export const MIN_SECONDS = 5;
const KEEP = 300;

export const progressKey = (playlistId: string) => `progress.${playlistId}`;

export class WatchProgress {
    private list: Watched[] = [];
    private readonly listeners = new Set<(list: readonly Watched[]) => void>();
    private readonly store: KeyValueStore;
    private readonly key: string;
    private readonly now: () => number;

    constructor(store: KeyValueStore, key: string, now: () => number = Date.now) {
        this.store = store;
        this.key = key;
        this.now = now;
    }

    async load(): Promise<readonly Watched[]> {
        try {
            const raw = await this.store.get(this.key);
            const parsed: unknown = raw ? JSON.parse(raw) : [];
            this.list = Array.isArray(parsed) ? parsed.filter(isWatched) : [];
        } catch {
            this.list = [];
        }
        this.tell();
        return this.list;
    }

    /** Where `id` was left, in seconds; 0 when it wasn't, or was watched to the end. */
    position(id: string): number {
        return this.list.find((w) => w.id === id)?.at ?? 0;
    }

    get all(): readonly Watched[] {
        return this.list;
    }

    /** Keeps where it is; near the end (or under 5 seconds in) it's forgotten, so it starts over next time. */
    async save(id: string, titleId: string, at: number, length: number): Promise<void> {
        const rest = this.list.filter((w) => w.id !== id);
        const done = at < MIN_SECONDS || (length > 0 && at >= length * DONE);
        this.list = done
            ? rest
            : [{ id, titleId, at: Math.round(at), length: Math.round(length), watchedAt: this.now() }, ...rest].slice(0, KEEP);
        this.tell();
        await this.store.set(this.key, JSON.stringify(this.list));
    }

    /** Adds positions kept before titles were recorded, as { id: seconds }; `titleOf` finds each one's title. */
    async adopt(old: Record<string, number>, titleOf: (id: string) => string | null): Promise<void> {
        if (this.list.length) return;
        const ids = Object.keys(old).reverse();
        const at = this.now();
        this.list = ids.flatMap((id, i) => {
            const titleId = titleOf(id);
            const seconds = old[id];
            return titleId && seconds >= MIN_SECONDS ? [{ id, titleId, at: Math.round(seconds), length: 0, watchedAt: at - i }] : [];
        });
        if (!this.list.length) return;
        this.tell();
        await this.store.set(this.key, JSON.stringify(this.list));
    }

    /** The newest unfinished one per movie or show, newest first. */
    continueWatching(limit = 20): Watched[] {
        const seen = new Set<string>();
        const out: Watched[] = [];
        for (const w of [...this.list].sort((a, b) => b.watchedAt - a.watchedAt)) {
            if (seen.has(w.titleId)) continue;
            seen.add(w.titleId);
            out.push(w);
            if (out.length >= limit) break;
        }
        return out;
    }

    onChange(listener: (list: readonly Watched[]) => void): () => void {
        this.listeners.add(listener);
        return () => void this.listeners.delete(listener);
    }

    private tell(): void {
        for (const l of this.listeners) {
            try {
                l(this.list);
            } catch {
                // A screen's listener failing never stops the others.
            }
        }
    }
}

function isWatched(x: unknown): x is Watched {
    const w = x as Watched;
    return !!w && typeof w.id === "string" && typeof w.titleId === "string" && typeof w.at === "number" && typeof w.watchedAt === "number";
}
