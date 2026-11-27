// The app's state below the screens: playlists, the lists read from the device, the guide, refresh with its steps,
// the Tuned account and Premium. Screens read the stores here and call the functions; nothing here draws.
import {
    TunedAccount,
    core,
    loadEpisodes,
    loadGuide,
    loadPlaylist,
    LoadError,
    playlistName,
    redactor,
    samePlaylist,
    type AccountState,
    type Item,
    type Kind,
    type PlaylistLogin,
    type Programme,
    type Step,
    SIGNED_OUT,
} from "@tuned/shared";
import { keepFrom } from "@tuned/shared/guide";
import { gunzipSync } from "fflate";
import { clearEverything, clearGuide, dropGeneration, kv, putItems, putProgrammes, readGuide, readKind } from "./db.ts";
import { log, safely } from "./log.ts";
import { tvFetch } from "./net.ts";
import { forgetKey, seal, unseal } from "./secret.ts";
import { store } from "./state.ts";
import { deviceName, onTv } from "./tizen.ts";

export interface SavedPlaylist {
    readonly id: string;
    readonly login: PlaylistLogin;
    /** The generation in use: items are kept under it. */
    readonly gen: number;
    readonly guideUrl: string;
    readonly refreshedAt: number;
    /** The server's time zone for catch-up links (Xtream's `server_info.timezone`); the TV's own when unknown. */
    readonly timezone?: string | null;
}

export interface Refresh {
    readonly running: boolean;
    readonly step: Step | null;
    readonly startedAt: number;
    readonly error: string | null;
    /** "done" once it ended well, for the screen's "Up to date" line. */
    readonly finishedAt: number;
}

export interface Settings {
    readonly channelNumbers: boolean;
    readonly hideAdult: boolean;
    /** Live TV as a grid guide instead of a list (playlist.md › Grid guide); the list stays the default, as on Android. */
    readonly guideGrid: boolean;
}

/** Days of guide kept ahead, and how far the grid scrolls. */
export const GUIDE_DAYS = 3;

export const playlists = store<SavedPlaylist[]>([]);
export const currentId = store<string | null>(null);
export const refresh = store<Refresh>({ running: false, step: null, startedAt: 0, error: null, finishedAt: 0 });
export const account = store<AccountState>(SIGNED_OUT);
export const settings = store<Settings>({ channelNumbers: true, hideAdult: true, guideGrid: false });
export const favourites = store<Set<string>>(new Set());
/** Bumped whenever the lists on the device change, so screens read them again. */
export const libraryVersion = store(0);
export const guideVersion = store(0);
export const ready = store(false);

export const tunedAccount = new TunedAccount({
    fetch: tvFetch,
    store: kv,
    crypto: crypto as never,
    gunzip: async (bytes) => gunzipSync(bytes),
    device: { kind: "tv", build: "tizen", name: deviceName() },
    // A desktop browser (development, the e2e test) can't call the server across origins, so it goes through its own
    // origin, which Vite or the test's server passes on; the TV calls tunedplayer.com itself.
    base: onTv() ? undefined : location.origin,
    log,
});

const KEYS = { playlists: "playlists", current: "playlist.current", settings: "settings", favourites: (id: string) => `favourites.${id}` };

export const current = (): SavedPlaylist | null => playlists.get().find((p) => p.id === currentId.get()) ?? null;

async function savePlaylists(): Promise<void> {
    await kv.set(KEYS.playlists, await seal(JSON.stringify(playlists.get())));
    const id = currentId.get();
    if (id) await kv.set(KEYS.current, id);
    else await kv.remove(KEYS.current);
}

/** Reads everything kept on the TV; the first screen shows from it at once. */
export async function start(): Promise<void> {
    await safely(
        "Start",
        async () => {
            const saved = JSON.parse((await unseal(await kv.get(KEYS.playlists))) ?? "[]") as SavedPlaylist[];
            playlists.set(Array.isArray(saved) ? saved : []);
            const id = await kv.get(KEYS.current);
            currentId.set(playlists.get().some((p) => p.id === id) ? id : (playlists.get()[0]?.id ?? null));
            const s = await kv.get(KEYS.settings);
            if (s) settings.set({ ...settings.get(), ...(JSON.parse(s) as Partial<Settings>) });
            account.set(await tunedAccount.state());
            await loadFavourites();
        },
        undefined,
    );
    ready.set(true);
    // Network work waits until the first screen is up (speed.md › QuietStart).
    setTimeout(() => {
        void safely("Account", async () => account.set(await tunedAccount.refresh()), undefined);
        const p = current();
        if (p && Date.now() - p.refreshedAt > REFRESH_EVERY) void refreshNow();
    }, 1500);
    setInterval(() => {
        const p = current();
        if (p && !refresh.get().running && Date.now() - p.refreshedAt > REFRESH_EVERY) void refreshNow();
    }, 15 * 60_000);
}

/** The playlist and guide refresh when the app opens and every few hours while it's open: Tizen has no background. */
const REFRESH_EVERY = 6 * 3_600_000;

export async function saveSettings(next: Partial<Settings>): Promise<void> {
    settings.set({ ...settings.get(), ...next });
    await kv.set(KEYS.settings, JSON.stringify(settings.get()));
    libraryVersion.set((v) => v + 1);
}

/** Free: one playlist. More than one is Premium, through the Tuned account (premium.md). */
export const canAddPlaylist = (): boolean => playlists.get().length === 0 || account.get().premium;

/** Adds a playlist (or finds it if it's already saved), makes it the one in use and reads it. */
export async function addPlaylist(login: PlaylistLogin): Promise<SavedPlaylist> {
    const existing = playlists.get().find((p) => samePlaylist(p.login, login));
    const saved = existing ?? { id: `p${Date.now().toString(36)}`, login, gen: 0, guideUrl: "", refreshedAt: 0 };
    if (!existing) playlists.set([...playlists.get(), saved]);
    currentId.set(saved.id);
    await savePlaylists();
    await loadFavourites();
    libraryVersion.set((v) => v + 1);
    if (!saved.refreshedAt) void refreshNow();
    return saved;
}

export async function switchPlaylist(id: string): Promise<void> {
    currentId.set(id);
    await savePlaylists();
    await loadFavourites();
    cache.clear();
    guide.clear();
    libraryVersion.set((v) => v + 1);
    guideVersion.set((v) => v + 1);
}

export async function removePlaylist(id: string): Promise<void> {
    const p = playlists.get().find((x) => x.id === id);
    if (!p) return;
    await safely("Playlists", () => dropGeneration(p.id, p.gen), undefined);
    await safely("Playlists", () => clearGuide(p.id), undefined);
    await kv.remove(KEYS.favourites(id));
    playlists.set(playlists.get().filter((x) => x.id !== id));
    if (currentId.get() === id) currentId.set(playlists.get()[0]?.id ?? null);
    await savePlaylists();
    await switchPlaylist(currentId.get() ?? "");
}

/** Sign out: every playlist, the lists, the guide, the account and the key, as installed (onboarding.md). */
export async function signOutEverything(): Promise<void> {
    await safely("SignOut", () => tunedAccount.signOut(), SIGNED_OUT);
    await safely("SignOut", clearEverything, undefined);
    await safely("SignOut", forgetKey, undefined);
    playlists.set([]);
    currentId.set(null);
    account.set(SIGNED_OUT);
    favourites.set(new Set());
    cache.clear();
    guide.clear();
    libraryVersion.set((v) => v + 1);
}

/** Reads the playlist in use into a new generation, then its guide. Always ends by saying how it went. */
export async function refreshNow(): Promise<void> {
    const p = current();
    if (!p || refresh.get().running) return;
    const redact = redactor(p.login);
    const gen = p.gen + 1;
    refresh.set({ running: true, step: null, startedAt: Date.now(), error: null, finishedAt: 0 });
    try {
        const result = await loadPlaylist(tvFetch, p.login, {
            onStep: (step) => refresh.set((r) => ({ ...r, step })),
            onItems: (items) => putItems(p.id, gen, items),
        });
        const updated: SavedPlaylist = { ...p, gen, guideUrl: result.guideUrl, refreshedAt: Date.now(), timezone: result.timezone ?? null };
        playlists.set(playlists.get().map((x) => (x.id === p.id ? updated : x)));
        await savePlaylists();
        await safely("Refresh", () => dropGeneration(p.id, p.gen), undefined);
        cache.clear();
        libraryVersion.set((v) => v + 1);
        log("Refresh", `${result.live} channels, ${result.movies} movies, ${result.shows} shows`);
        await refreshGuide(updated);
        refresh.set({ running: false, step: null, startedAt: 0, error: null, finishedAt: Date.now() });
    } catch (error) {
        await safely("Refresh", () => dropGeneration(p.id, gen), undefined);
        const reason = error instanceof LoadError ? error.reason : "unreachable";
        log("Refresh", redact(String((error as Error)?.message ?? error)));
        refresh.set({ running: false, step: null, startedAt: 0, error: reason, finishedAt: 0 });
    }
}

async function refreshGuide(p: SavedPlaylist): Promise<void> {
    if (!p.guideUrl) return;
    // Each channel's guide from two hours ago, or from as far back as it keeps recordings (playlist.md › Catch-up).
    const now = Date.now();
    const recent = now - 2 * 3_600_000;
    const from = new Map<string, number>();
    for (const i of await kindItems("live")) {
        if (!i.tvgId) continue;
        const since = Math.min(recent, i.catchUpDays > 0 ? keepFrom(now, i.catchUpDays) : recent);
        from.set(i.tvgId, Math.min(from.get(i.tvgId) ?? since, since));
    }
    if (!from.size) return;
    const to = now + GUIDE_DAYS * 86_400_000;
    const redact = redactor(p.login);
    refresh.set((r) => ({ ...r, step: { id: "guide", number: 1, count: 1, progress: null } }));
    await safely(
        "Guide",
        async () => {
            await clearGuide(p.id);
            const count = await loadGuide(
                tvFetch,
                p.guideUrl,
                (channel, start, stop) => {
                    const since = from.get(channel);
                    return since !== undefined && stop > since && start < to;
                },
                (piece) => (piece.programmes.length ? putProgrammes(p.id, piece.programmes) : undefined),
                { redact, onProgress: (progress) => refresh.set((r) => ({ ...r, step: { id: "guide", number: 1, count: 1, progress } })) },
            );
            log("Guide", `${count} programmes`);
        },
        undefined,
    );
    guide.clear();
    guideVersion.set((v) => v + 1);
}

// ---- The lists on the device ----

const cache = new Map<Kind, Promise<Item[]>>();

/** Every item of a kind for the playlist in use, in playlist order, read once and kept until it changes. */
export function kindItems(kind: Kind): Promise<Item[]> {
    const p = current();
    if (!p || !p.gen) return Promise.resolve([]);
    let found = cache.get(kind);
    if (!found) {
        found = safely("Library", () => readKind(p.id, p.gen, kind), [] as Item[]);
        cache.set(kind, found);
    }
    return found;
}

export interface Group {
    readonly id: string;
    readonly name: string;
    readonly count: number;
}

export const ALL = "__all";
export const FAVOURITES = "__favourites";

/** The groups a kind's items fall in, in playlist order, after All and Favourites. Adult groups are hidden by default. */
export function groupsOf(items: Item[]): Group[] {
    const counts = new Map<string, number>();
    for (const i of items) counts.set(i.group, (counts.get(i.group) ?? 0) + 1);
    const favs = items.filter((i) => favourites.get().has(i.id)).length;
    return [
        { id: ALL, name: "All", count: items.length },
        ...(favs ? [{ id: FAVOURITES, name: "Favourites", count: favs }] : []),
        ...[...counts].map(([name, count]) => ({ id: name, name, count })),
    ];
}

export function visibleItems(items: Item[]): Item[] {
    return settings.get().hideAdult ? items.filter((i) => !i.adult) : items;
}

export function inGroup(items: Item[], group: string): Item[] {
    if (group === ALL) return items;
    if (group === FAVOURITES) return items.filter((i) => favourites.get().has(i.id));
    return items.filter((i) => i.group === group);
}

/** Shows from their episodes or stand-ins, one row per show. */
export function shows(items: Item[]): Item[] {
    const seen = new Map<string, Item>();
    for (const i of items) {
        const key = core.showKey(i.showName ?? i.name);
        if (!seen.has(key)) seen.set(key, i);
    }
    return [...seen.values()];
}

export async function episodesOf(show: Item): Promise<Item[]> {
    const p = current();
    if (!p) return [];
    const all = await kindItems("series");
    const key = core.showKey(show.showName ?? show.name);
    const own = all.filter((i) => core.showKey(i.showName ?? i.name) === key && !i.url.startsWith("xtream-series:"));
    if (own.length) return own.sort((a, b) => a.season - b.season || a.episode - b.episode);
    return safely("Episodes", () => loadEpisodes(tvFetch, p.login, show), []);
}

export function search(items: Item[], query: string): Item[] {
    const q = core.searchText(query);
    if (!q) return [];
    const words = q.split(" ");
    return items.filter((i) => {
        const text = core.searchText(i.name);
        return words.every((w) => text.includes(w));
    });
}

async function loadFavourites(): Promise<void> {
    const id = currentId.get();
    const raw = id ? await kv.get(KEYS.favourites(id)) : null;
    favourites.set(new Set(raw ? (JSON.parse(raw) as string[]) : []));
}

export async function toggleFavourite(item: Item): Promise<void> {
    const id = currentId.get();
    if (!id) return;
    const next = new Set(favourites.get());
    if (next.has(item.id)) next.delete(item.id);
    else next.add(item.id);
    favourites.set(next);
    await kv.set(KEYS.favourites(id), JSON.stringify([...next]));
}

// ---- The guide ----

const guide = new Map<string, Promise<Map<string, Programme[]>>>();

function guideFor(listId: string): Promise<Map<string, Programme[]>> {
    let found = guide.get(listId);
    if (!found) {
        found = safely(
            "Guide",
            async () => {
                const byChannel = new Map<string, Programme[]>();
                for (const p of await readGuide(listId)) {
                    const list = byChannel.get(p.channel) ?? [];
                    list.push(p);
                    byChannel.set(p.channel, list);
                }
                for (const list of byChannel.values()) list.sort((a, b) => a.start - b.start);
                return byChannel;
            },
            new Map(),
        );
        guide.set(listId, found);
    }
    return found;
}

/** What's on a channel now and next, from the guide on the device. */
export async function nowNext(item: Item, now = Date.now()): Promise<{ now: Programme | null; next: Programme | null }> {
    const id = currentId.get();
    if (!id || !item.tvgId) return { now: null, next: null };
    const list = (await guideFor(id)).get(item.tvgId) ?? [];
    const at = list.findIndex((p) => p.stop > now);
    if (at < 0) return { now: null, next: null };
    return list[at].start <= now ? { now: list[at], next: list[at + 1] ?? null } : { now: null, next: list[at] };
}

/** Every channel's programmes by guide id, sorted, for the grid guide to read without waiting row by row. */
export async function guideByChannel(): Promise<Map<string, Programme[]>> {
    const id = currentId.get();
    return id ? guideFor(id) : new Map();
}

export async function programmes(item: Item): Promise<Programme[]> {
    const id = currentId.get();
    if (!id || !item.tvgId) return [];
    return (await guideFor(id)).get(item.tvgId) ?? [];
}

export const nameOf = playlistName;
export { redactor };
