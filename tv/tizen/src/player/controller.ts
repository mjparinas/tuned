// What plays, and the remote's moves while it does: channel up and down, typing a number, flipping back to the last
// channel, pausing and seeking a film. Every open goes through OneConnection, so there is never a second stream
// (scope.md), and a failed open leaves nothing open and says so on screen with Try again.
import { OneConnection, core, type Item, type StreamPlayer } from "@tuned/shared";
import { kv } from "../db.ts";
import { log } from "../log.ts";
import { store } from "../state.ts";
import { redactor, current } from "../app.ts";

export interface TvPlayer extends StreamPlayer {
    pause(): void;
    resume(): void;
    seek(ms: number): void;
    position(): number;
    duration(): number;
    tracks(): { index: number; type: string; label: string }[];
    selectTrack(type: string, index: number): void;
}

export interface Playback {
    readonly item: Item | null;
    /** The list channel up and down walks through (the group the channel was picked from). */
    readonly list: readonly Item[];
    readonly state: "idle" | "opening" | "playing" | "failed";
    readonly buffering: boolean;
    readonly paused: boolean;
    /** Digits typed so far for a channel number. */
    readonly dial: string;
    /** The channel before this one, for the remote's flip-back key. */
    readonly previous: Item | null;
}

const IDLE: Playback = { item: null, list: [], state: "idle", buffering: false, paused: false, dial: "", previous: null };
export const playback = store<Playback>(IDLE);

let player: TvPlayer | null = null;
let guard: OneConnection | null = null;
let ticket = 0;
let dialTimer: ReturnType<typeof setTimeout> | undefined;
let saveTimer: ReturnType<typeof setInterval> | undefined;

export function usePlayer(p: TvPlayer): void {
    player = p;
    guard = new OneConnection(p);
}

/** For the e2e test's check: never more than one connection open. */
export const connections = (): { open: number; highest: number } => ({ open: guard?.open ?? 0, highest: guard?.highest ?? 0 });

export const playerEvents = {
    onBuffering: (buffering: boolean) => playback.set((p) => ({ ...p, buffering })),
    onEnded: () => {
        const p = playback.get();
        if (!p.item || p.state !== "playing") return;
        // A live channel never really ends: the provider dropped it, so it reconnects (still one connection).
        if (p.item.kind === "live") {
            log("Player", "The live stream ended; reconnecting");
            void play(p.item, p.list);
            return;
        }
        void forgetPosition(p.item);
        void stop();
    },
    onError: (message: string) => {
        log("Player", `Stream error: ${message}`);
        playback.set((p) => (p.state === "idle" ? p : { ...p, state: "failed", buffering: false }));
    },
    onSubtitle: () => undefined,
};

const positionKey = (item: Item) => `position.${current()?.id ?? ""}.${item.id}`;

export async function savedPosition(item: Item): Promise<number> {
    if (item.kind === "live") return 0;
    const raw = await kv.get(positionKey(item)).catch(() => null);
    return raw ? Number(raw) || 0 : 0;
}

async function forgetPosition(item: Item): Promise<void> {
    await kv.remove(positionKey(item)).catch(() => undefined);
}

function savePosition(): void {
    const p = playback.get();
    if (!player || !p.item || p.item.kind === "live" || p.state !== "playing") return;
    const at = player.position();
    const length = player.duration();
    // Near the end counts as watched, so the next Play starts at the beginning.
    if (length > 0 && at > length * 0.95) void forgetPosition(p.item);
    else if (at > 60_000) void kv.set(positionKey(p.item), String(Math.floor(at))).catch(() => undefined);
}

/** Plays [item]; channel up and down then walk [list]. */
export async function play(item: Item, list: readonly Item[] = [item], startAt = 0): Promise<void> {
    if (!guard) return;
    const before = playback.get();
    const previous = before.item && before.item.id !== item.id && before.item.kind === "live" ? before.item : before.previous;
    savePosition();
    const mine = ++ticket;
    playback.set({ item, list, state: "opening", buffering: false, paused: false, dial: "", previous });
    const login = current()?.login;
    const redact = login ? redactor(login) : (s: string) => s;
    try {
        const headers = Object.fromEntries(core.headers(item.url).map(([k, v]) => [k, v]));
        await guard.play(core.address(item.url), { headers, startAt });
        if (mine !== ticket) return;
        playback.set((p) => ({ ...p, state: "playing" }));
        clearInterval(saveTimer);
        saveTimer = setInterval(savePosition, 10_000);
    } catch (error) {
        if (mine !== ticket) return;
        log("Player", `Couldn't open ${item.kind}: ${redact(String((error as Error)?.message ?? error))}`);
        playback.set((p) => ({ ...p, state: "failed" }));
    }
}

export async function retry(): Promise<void> {
    const p = playback.get();
    if (p.item) await play(p.item, p.list, await savedPosition(p.item));
}

/** Ends playback and closes the connection. */
export async function stop(): Promise<void> {
    if (playback.get().state === "idle") return;
    savePosition();
    clearInterval(saveTimer);
    clearTimeout(dialTimer);
    ticket++;
    const previous = playback.get().previous;
    playback.set({ ...IDLE, previous });
    await guard?.stop().catch((error: unknown) => log("Player", `Close failed: ${String(error)}`));
}

/** Channel up (+1) or down (-1) through the list, wrapping round. */
export function step(by: 1 | -1): void {
    const p = playback.get();
    if (!p.item || p.item.kind !== "live" || p.list.length < 2) return;
    const at = Math.max(
        0,
        p.list.findIndex((i) => i.id === p.item?.id),
    );
    const next = p.list[(at + by + p.list.length) % p.list.length];
    void play(next, p.list);
}

export function flipBack(): void {
    const p = playback.get();
    if (p.previous) void play(p.previous, p.list.some((i) => i.id === p.previous?.id) ? p.list : [p.previous]);
}

/** A number key: digits gather for 1.5 seconds (or four digits), then that channel number plays. */
export function dial(digit: number, channels: readonly Item[]): void {
    const typed = (playback.get().dial + String(digit)).slice(-4);
    playback.set((p) => ({ ...p, dial: typed }));
    clearTimeout(dialTimer);
    const go = () => {
        playback.set((p) => ({ ...p, dial: "" }));
        const number = Number(typed);
        const found = channels.find((c) => c.number === number);
        if (found) void play(found, channels);
    };
    if (typed.length >= 4) go();
    else dialTimer = setTimeout(go, 1500);
}

export function togglePause(): void {
    const p = playback.get();
    if (!player || p.state !== "playing") return;
    if (p.paused) player.resume();
    else player.pause();
    playback.set((x) => ({ ...x, paused: !x.paused }));
}

export function seekBy(ms: number): void {
    const p = playback.get();
    if (!player || p.state !== "playing" || !p.item || p.item.kind === "live") return;
    const length = player.duration();
    const to = Math.max(0, player.position() + ms);
    player.seek(length > 0 ? Math.min(to, length - 1000) : to);
}

export const position = (): { at: number; length: number } => ({ at: player?.position() ?? 0, length: player?.duration() ?? 0 });
export const tracks = () => player?.tracks() ?? [];
export const selectTrack = (type: string, index: number) => player?.selectTrack(type, index);
