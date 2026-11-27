// Guide reminders for the TV apps (Android's Remind me, docs/decisions/playlist.md): programmes a person asked to be
// told about, kept per playlist in the app's own store. Neither TV can wake an app that isn't running, so they're told
// while it's open: `due(now)` gives each one once, when it starts (or if the app opens within its first minutes), and
// forgets it. `@tuned/shared/reminders`.
import type { KeyValueStore } from "./platform.ts";

export interface Reminder {
    /** The app's channel id. */
    channelId: string;
    channelName: string;
    title: string;
    /** Epoch milliseconds. */
    start: number;
    stop: number;
}

/** How long after a programme starts it's still worth saying, for an app opened late. */
export const LATE = 10 * 60_000;
const KEEP = 100;

export const remindersKey = (playlistId: string) => `reminders.${playlistId}`;

const same = (a: Pick<Reminder, "channelId" | "start">, b: Pick<Reminder, "channelId" | "start">) =>
    a.channelId === b.channelId && a.start === b.start;

export class Reminders {
    private list: Reminder[] = [];
    private readonly listeners = new Set<(list: readonly Reminder[]) => void>();
    private readonly store: KeyValueStore;
    private readonly key: string;

    constructor(store: KeyValueStore, key: string) {
        this.store = store;
        this.key = key;
    }

    async load(now: number = Date.now()): Promise<readonly Reminder[]> {
        try {
            const raw = await this.store.get(this.key);
            const parsed: unknown = raw ? JSON.parse(raw) : [];
            this.list = Array.isArray(parsed) ? parsed.filter(isReminder) : [];
        } catch {
            this.list = [];
        }
        // Ones that ended while the app was closed are gone; they can't be watched any more.
        this.list = this.list.filter((r) => r.stop > now);
        this.tell();
        return this.list;
    }

    get all(): readonly Reminder[] {
        return this.list;
    }

    has(channelId: string, start: number): boolean {
        return this.list.some((r) => same(r, { channelId, start }));
    }

    /** Sets or clears a reminder; true when it's now set. Only programmes that haven't started can be set. */
    async toggle(reminder: Reminder, now: number = Date.now()): Promise<boolean> {
        const on = !this.has(reminder.channelId, reminder.start);
        if (on && reminder.start <= now) return false;
        this.list = on
            ? [...this.list, reminder].sort((a, b) => a.start - b.start).slice(0, KEEP)
            : this.list.filter((r) => !same(r, reminder));
        this.tell();
        await this.save();
        return on;
    }

    /** The ones that have started (within the last 10 minutes), each given once and then forgotten. */
    async due(now: number = Date.now()): Promise<Reminder[]> {
        const due = this.list.filter((r) => r.start <= now);
        if (!due.length) return [];
        this.list = this.list.filter((r) => r.start > now);
        this.tell();
        await this.save();
        return due.filter((r) => now - r.start <= LATE && r.stop > now);
    }

    /** When the next one starts, so the app can wait just that long; null when there's none. */
    next(): number | null {
        return this.list[0]?.start ?? null;
    }

    onChange(listener: (list: readonly Reminder[]) => void): () => void {
        this.listeners.add(listener);
        return () => void this.listeners.delete(listener);
    }

    private async save(): Promise<void> {
        await this.store.set(this.key, JSON.stringify(this.list));
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

function isReminder(x: unknown): x is Reminder {
    const r = x as Reminder;
    return (
        !!r &&
        typeof r.channelId === "string" &&
        typeof r.title === "string" &&
        typeof r.start === "number" &&
        typeof r.stop === "number" &&
        typeof r.channelName === "string"
    );
}
