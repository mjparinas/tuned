// The app's state below the screens: playlists, the lists read from the device, the guide, refresh with its steps,
// the Tuned account and Premium. Screens read the stores here and call the functions; nothing here draws.
import { forgetServices, readServices, takeServicesFromBackup } from "./services.ts";
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
import {
    NO_PROFILES,
    favouritesOnTv,
    forKids,
    profilesFromBackup,
    webPbkdf2,
    type AccountProfiles,
    type Profile,
} from "@tuned/shared/profiles";
import { NO_LISTS, listOnTv, listsFromBackup, withEdit, type AccountLists } from "@tuned/shared/lists";
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
/** The account's "Who's watching?" profiles, as the phone made them (profiles.md); none when signed out. */
export const profiles = store<AccountProfiles>(NO_PROFILES);
export const profileId = store<string | null>(null);
/** Whether "Who's watching?" was answered since the app opened: it asks each time, as Android does. */
export const profilePicked = store(false);
/** The account's own lists for each profile, and its renamed channels and logos (lists.md); none when signed out. */
export const accountLists = store<AccountLists>(NO_LISTS);
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

const KEYS = {
    playlists: "playlists",
    current: "playlist.current",
    settings: "settings",
    profiles: "profiles",
    profile: "profile.current",
    lists: "lists.account",
    /** Each playlist's favourites, and with profiles each profile's own. */
    favourites: (id: string, profile?: string | null) => (profile ? `favourites.${id}.${profile}` : `favourites.${id}`),
};

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
            const kept = JSON.parse((await unseal(await kv.get(KEYS.profiles))) ?? "null") as AccountProfiles | null;
            if (kept && Array.isArray(kept.profiles)) profiles.set(kept);
            const lists = JSON.parse((await unseal(await kv.get(KEYS.lists))) ?? "null") as AccountLists | null;
            if (lists && lists.lists && lists.edits) accountLists.set(lists);
            const who = await kv.get(KEYS.profile);
            profileId.set(profiles.get().profiles.some((x) => x.id === who) ? who : null);
            profilePicked.set(profiles.get().profiles.length < 2);
            quiet = true;
            account.set(await tunedAccount.state());
            quiet = false;
            await loadFavourites();
        },
        undefined,
    );
    ready.set(true);
    void readServices();
    // Network work waits until the first screen is up (speed.md › QuietStart).
    setTimeout(() => {
        void safely(
            "Account",
            async () => {
                account.set(await tunedAccount.refresh());
                await refreshProfiles();
            },
            undefined,
        );
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
    cache.clear();
    guide.clear();
    await loadFavourites();
    libraryVersion.set((v) => v + 1);
    guideVersion.set((v) => v + 1);
}

export async function removePlaylist(id: string): Promise<void> {
    const p = playlists.get().find((x) => x.id === id);
    if (!p) return;
    await safely("Playlists", () => dropGeneration(p.id, p.gen), undefined);
    await safely("Playlists", () => clearGuide(p.id), undefined);
    await kv.remove(KEYS.favourites(id));
    for (const x of profiles.get().profiles) await kv.remove(KEYS.favourites(id, x.id));
    playlists.set(playlists.get().filter((x) => x.id !== id));
    if (currentId.get() === id) currentId.set(playlists.get()[0]?.id ?? null);
    await savePlaylists();
    await switchPlaylist(currentId.get() ?? "");
}

/** Sign out: every playlist, the lists, the guide, the account and the key, as installed (onboarding.md). */
export async function signOutEverything(): Promise<void> {
    await safely("SignOut", () => tunedAccount.signOut(), SIGNED_OUT);
    await safely("SignOut", clearEverything, undefined);
    await safely("SignOut", forgetServices, undefined);
    await safely("SignOut", forgetKey, undefined);
    playlists.set([]);
    currentId.set(null);
    account.set(SIGNED_OUT);
    profiles.set(NO_PROFILES);
    accountLists.set(NO_LISTS);
    profileId.set(null);
    profilePicked.set(true);
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
        await loadFavourites();
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
        found = safely(
            "Library",
            async () => {
                const items = await readKind(p.id, p.gen, kind);
                // Channels renamed or given a logo on the phone or website show that way here too (lists.md).
                const { edits } = accountLists.get();
                if (kind !== "live" || !Object.keys(edits).length) return items;
                return items.map((i) => {
                    const edited = withEdit(i, edits);
                    if (edited !== i) playlistNames.set(i.id, i.name);
                    return edited;
                });
            },
            [] as Item[],
        );
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
const LIST = "__list:";

/** A renamed channel's name in the playlist, so search finds it by either name. */
const playlistNames = new Map<string, string>();
export const playlistNameOf = (item: Item): string | undefined => playlistNames.get(item.id);

/**
 * The profile whose Favourites and lists live in the account: the one picked, or the only one. Null when signed out
 * or the account has no profiles yet, and then Favourites stay on the TV.
 */
export function accountProfile(): Profile | null {
    const list = profiles.get().profiles;
    return currentProfile() ?? (list.length === 1 ? list[0] : null);
}

/** Your own lists for the profile in use; Premium, as on Android (lists.md), so none on a free account. */
export function myLists(): AccountLists["lists"][string] {
    const p = accountProfile();
    return p && account.get().premium ? (accountLists.get().lists[p.id] ?? []) : [];
}

/**
 * The groups a kind's items fall in, in playlist order, after All, Favourites and each of your own lists that holds
 * something of that kind (Live TV and Movies; shows aren't added to lists). Adult groups are hidden by default.
 */
export function groupsOf(items: Item[]): Group[] {
    const counts = new Map<string, number>();
    for (const i of items) counts.set(i.group, (counts.get(i.group) ?? 0) + 1);
    const favs = items.filter((i) => favourites.get().has(i.id)).length;
    const lists = items[0]?.kind === "series" ? [] : myLists();
    return [
        { id: ALL, name: "All", count: items.length },
        ...(favs ? [{ id: FAVOURITES, name: "Favourites", count: favs }] : []),
        ...lists.map((l) => ({ id: LIST + l.id, name: l.name, count: listOnTv(l, items).length })).filter((g) => g.count > 0),
        ...[...counts].map(([name, count]) => ({ id: name, name, count })),
    ];
}

export function visibleItems(items: Item[]): Item[] {
    if (currentProfile()?.kids) return items.filter(forKids);
    return settings.get().hideAdult ? items.filter((i) => !i.adult) : items;
}

export function inGroup(items: Item[], group: string): Item[] {
    if (group === ALL) return items;
    if (group === FAVOURITES) return items.filter((i) => favourites.get().has(i.id));
    if (group.startsWith(LIST)) {
        const list = myLists().find((l) => LIST + l.id === group);
        return list ? listOnTv(list, items) : [];
    }
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
        const text = core.searchText(i.name + " " + (playlistNames.get(i.id) ?? ""));
        return words.every((w) => text.includes(w));
    });
}

/**
 * The playlist's favourites for the profile in use. Signed in to an account with profiles, Favourites are the
 * account's, as the phone and website keep them, matched to this playlist's items by Android's id; a show, which
 * Android doesn't favourite, stays on the TV. Otherwise they're kept on the TV for each playlist.
 */
export async function loadFavourites(): Promise<void> {
    const id = currentId.get();
    const raw = id ? await kv.get(KEYS.favourites(id, currentProfile()?.id)) : null;
    const kept = safelyParse(raw);
    const profile = accountProfile();
    if (!profile || !current()?.gen) return void favourites.set(new Set(kept));
    const items = [...(await kindItems("live")), ...(await kindItems("movie"))];
    const shows = new Set((await kindItems("series")).map((i) => i.id));
    favourites.set(new Set([...favouritesOnTv(profile, items), ...kept.filter((x) => shows.has(x))]));
}

function safelyParse(raw: string | null): string[] {
    try {
        const list: unknown = raw ? JSON.parse(raw) : [];
        return Array.isArray(list) ? list.filter((x): x is string => typeof x === "string") : [];
    } catch {
        return [];
    }
}

/** Stars or unstars one of the favourites kept on the TV (a show's, or every one when Favourites aren't the account's). */
export async function keepFavourite(itemId: string, on: boolean): Promise<void> {
    const id = currentId.get();
    if (!id) return;
    const key = KEYS.favourites(id, currentProfile()?.id);
    const kept = new Set(safelyParse(await kv.get(key)));
    if (on) kept.add(itemId);
    else kept.delete(itemId);
    await kv.set(key, JSON.stringify([...kept]));
}

/**
 * Takes the profiles, lists and channel edits from the account's backup, as read or as the TV just saved it, and
 * keeps them for when the TV is offline. Edits that changed redraw the lists.
 */
export async function takeBackup(text: string): Promise<void> {
    const read = profilesFromBackup(text);
    const lists = listsFromBackup(text);
    const editsChanged = JSON.stringify(lists.edits) !== JSON.stringify(accountLists.get().edits);
    profiles.set(read);
    accountLists.set(lists);
    await kv.set(KEYS.profiles, await seal(JSON.stringify(read)));
    await kv.set(KEYS.lists, await seal(JSON.stringify(lists)));
    if (editsChanged) {
        cache.clear();
        playlistNames.clear();
    }
    await loadFavourites();
    libraryVersion.set((v) => v + 1);
    await takeServicesFromBackup(text);
}

// ---- Profiles (profiles.md) ----

export const currentProfile = (): Profile | null => profiles.get().profiles.find((p) => p.id === profileId.get()) ?? null;

/** PBKDF2 for checking a PIN against the hash the phone made, on the TV's own crypto. */
export const pbkdf2 = webPbkdf2(crypto.subtle as never);

// Signing in reads the account's profiles; signing out of it (here, or the TV removed on the website) forgets them.
let quiet = false;
account.subscribe((state) => {
    if (quiet) return;
    if (state.signedIn && !profiles.get().profiles.length) void refreshProfiles();
    if (!state.signedIn && profiles.get().profiles.length) void forgetProfiles();
});

async function forgetProfiles(): Promise<void> {
    profiles.set(NO_PROFILES);
    accountLists.set(NO_LISTS);
    await kv.remove(KEYS.profiles);
    await kv.remove(KEYS.lists);
    cache.clear();
    playlistNames.clear();
    await useProfile(null);
}

/** Reads the profiles from the account's backup. Offline, the ones read last stay. */
export async function refreshProfiles(): Promise<void> {
    if (!account.get().signedIn) return;
    const text = await safely("Profiles", () => tunedAccount.backup(), null);
    if (text === null) return;
    await takeBackup(text);
    const read = profiles.get();
    if (profileId.get() && !currentProfile()) await useProfile(null);
    if (read.profiles.length > 1 && !profileId.get()) profilePicked.set(false);
    else if (read.profiles.length < 2) profilePicked.set(true);
}

/** Watches as `id` from now on: its favourites, and kids mode for a kids profile. The PIN was asked already. */
export async function useProfile(id: string | null): Promise<void> {
    profileId.set(id);
    profilePicked.set(true);
    if (id) await kv.set(KEYS.profile, id);
    else await kv.remove(KEYS.profile);
    await loadFavourites();
    libraryVersion.set((v) => v + 1);
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
