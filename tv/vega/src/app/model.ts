// The app's state and what changes it, apart from the screens, so it's tested on Node. Opening shows the channels
// saved on the stick at once and refreshes after the first screen is up (AGENTS.md: nothing waits on the network);
// every refresh, sign-in and account check reports progress and how it ended. Movies and shows load after Live TV,
// in the background, so they never hold up the channels.
import { ACCOUNT_BASE, TunedAccount, type AccountState as SharedAccount } from "@tuned/shared/account";
import { Favourites, favouritesKey } from "@tuned/shared/favourites";
import {
    isKids,
    lockFor,
    NO_PROFILES,
    PinThrottle,
    profilesFromBackup,
    verifyPin,
    type AccountProfiles,
    type Profile,
} from "@tuned/shared/profiles";
import { androidIdHex, hexToId, idToHex, xtreamShowLink } from "@tuned/shared/stableid";
import {
    changeBackup,
    listsFromBackup,
    NO_LISTS,
    type AccountLists,
    type ListChange,
    type ListProblem,
    type UserList,
} from "@tuned/shared/lists";
import { visible, type Hiding } from "@tuned/shared/filters";
import {
    ChannelHealth,
    EVENTS_AHEAD_MS,
    eventsInNames,
    followsFromBackup,
    groupEvents,
    hasWords,
    healthKey,
    rankForTune,
    regionTag,
    sportyChannel,
    tuneWords,
    withNamed,
    type EventProgramme,
    type LiveEvent,
    type TuneCandidate,
} from "@tuned/shared/events";
import { catchUpLink, keepFrom, MAX_CATCH_UP_DAYS, replayable } from "@tuned/shared/guide";
import { Reminders, remindersKey, type Reminder } from "@tuned/shared/reminders";
import { XmltvReader } from "@tuned/shared/xmltv";
import type { Fetch, KeyValueStore } from "@tuned/shared/platform";
import { progressKey, WatchProgress, type Watched } from "@tuned/shared/progress";
import { isSortOrder, nextSort, sortItems, type SortOrder } from "@tuned/shared/sort";
import { search as searchItems } from "@tuned/shared/search";
import { Simkl, simklFromBackup, simklProgress, simklScrobbleFromBackup, type ScrobbleMedia, type SignInCode } from "@tuned/shared/simkl";
import { Trakt, traktFromBackup, traktScrobbleFromBackup } from "@tuned/shared/trakt";
import { Tmdb, type Details, type EpisodeInfo } from "@tuned/shared/tmdb";
import type { Http } from "../core/http";
import { addSecret, clearSecrets, launchSafely, log } from "../core/log";
import type { Category, Channel, Episode, GuideProgramme, Library, Lineup, Login, Playable, Programme, Title } from "../core/models";
import { OneStream, type StreamPlayer } from "../core/oneStream";
import { downloadLibrary, downloadLineup, failureText, isStale } from "../core/playlist";
import { eta, type StepProgress } from "../core/progress";
import { PREFIX, storage, type SavedPlaylist, type Storage, type Store } from "../core/storage";
import { gunzip, nobleCrypto, noblePbkdf2 } from "../platform/crypto";
import { t } from "../ui/strings";
import { backupLogins, playlistName, sameLogin } from "./logins";
import { epgTableUrl, parseEpgTable, parseEpisodes, parseShortEpg, seriesInfoUrl, shortEpgUrl } from "../core/xtream";

/** The Tuned account as the screens show it; null when the stick isn't signed in. */
export interface AccountState {
    email: string;
    /** What the server last said; kept so an outage never locks a paying person out. */
    premium: boolean;
    /** "full" alone locks Premium while the account has it (docs/decisions/accounts.md). */
    seat: "active" | "full" | "none" | "unknown";
    checkedAt: number;
}

const accountView = (s: SharedAccount): AccountState | null =>
    s.signedIn ? { email: s.email, premium: s.premium, seat: s.seat ?? "unknown", checkedAt: s.checkedAt } : null;

/** @tuned/shared's store over the stick's, under the same "tuned:" prefix, so signing out clears it with the rest. */
const sharedStore = (store: Store): KeyValueStore => ({
    get: (key) => store.get(PREFIX + key),
    set: (key, value) => store.set(PREFIX + key, value),
    remove: (key) => store.remove([PREFIX + key]),
});

export type Route =
    | "boot"
    | "signin"
    | "manual"
    | "loading"
    | "live"
    | "events"
    | "movies"
    | "shows"
    | "search"
    | "title"
    | "player"
    | "settings"
    | "account"
    | "profiles";

/** The category that holds what the person starred, first after All in Live TV, Movies and Shows. */
export const FAVOURITES = "__favourites";

/** The first playlist's id, which favourites and Continue watching were kept under before there could be several. */
const FIRST = "current";

/** The category of movies and shows started and not finished, first in Movies and Shows when there are any. */
export const CONTINUE = "__continue";

/** Settings › Content, and each tab's sort, kept on the stick. */
export interface Prefs extends Hiding {
    sortMovies: SortOrder;
    sortShows: SortOrder;
    /** Live TV as a guide grid instead of the list; the list stays the default, as on Android. */
    liveGrid: boolean;
    /** Settings › Auto-tune: how long a channel gets to show a picture before the next is tried, in seconds. */
    tuneWaitS: number;
}

/** Settings › Auto-tune's choices, as on Android (5 to 20 seconds, 8 by default). */
export const TUNE_WAITS = [5, 8, 12, 20] as const;

export const DEFAULT_PREFS: Prefs = {
    hideAdult: true,
    hide247: false,
    sortMovies: "provider",
    sortShows: "provider",
    liveGrid: false,
    tuneWaitS: 8,
};

/** At most this many sports channels' guides are read for Live events, so a huge playlist doesn't take all night. */
export const MAX_EVENT_ROWS = 300;

/** Auto-tune working through the channels that may show an event, one at a time (docs/decisions/auto-tune.md). */
export interface Tune {
    title: string;
    /** The channels to try, best first. */
    ids: string[];
    /** The one playing or being tried. */
    at: number;
    /** It has shown a picture. */
    found: boolean;
    /** Every channel was tried and none played. */
    failed: boolean;
}

/** Days of guide ahead the grid scrolls to. */
export const GUIDE_DAYS = 2;
const PREFS_KEY = "settings";
/** The account's profiles and the one last used, and the teams followed, as last read from its backup. */
const PROFILES_KEY = "profiles";
const PROFILE_KEY = "profile.current";
const FOLLOWS_KEY = "events.follows";
/** The account's own lists and its renamed channels, as last read (the Samsung app's key). */
const LISTS_KEY = "lists.account";
/** A list's category in Live TV and Movies: this and the list's Android id. */
export const LIST = "__list:";

/** One search result: a channel, a movie or a show. */
export type Found = { kind: "live"; channel: Channel } | { kind: "movie" | "show"; title: Title };

export interface Busy {
    busy: boolean;
    progress?: StepProgress | null;
    /** How it ended, in words, until the next try. */
    result?: { ok: boolean; text: string } | null;
}

export interface State {
    route: Route;
    login: Login | null;
    lineup: Lineup | null;
    /** The first download, shown full screen. */
    loading: { progress: StepProgress | null; error: string | null };
    /** A refresh while the channels show. */
    refresh: Busy;
    account: AccountState | null;
    accountCheck: Busy;
    category: string | null;
    channelId: string | null;
    guide: Record<string, Programme[]>;
    /** Movies and shows; null until read from the stick or downloaded. */
    library: Library | null;
    /** Downloading movies and shows, in its own steps. */
    libraryLoad: Busy;
    movieCategory: string | null;
    showCategory: string | null;
    /** The movie or show whose page is open. */
    title: Title | null;
    /** Xtream shows' episodes, read when a show is opened and kept for the session. */
    episodes: Record<string, Episode[]>;
    episodesLoad: { id: string | null; busy: boolean; failed: boolean };
    /** The movie or episode playing; null for live TV. */
    vod: Playable | null;
    /** Where each movie and episode was left, in seconds. */
    positions: Record<string, number>;
    /** The newest unfinished movie or episode per title, newest first (Continue Watching). */
    watching: readonly Watched[];
    prefs: Prefs;
    /** The ids starred, channels, movies and shows alike. */
    favourites: ReadonlySet<string>;
    /** What a star just did, said for a few seconds ("Added to Favourites"). */
    notice: { on: boolean; name: string; text?: string } | null;
    /** TMDB's details per title id; null when TMDB has no match. */
    details: Record<string, Details | null>;
    detailsLoad: { id: string | null; busy: boolean; failed: boolean };
    /** TMDB's episode names per "tmdbId:season". */
    seasons: Record<string, EpisodeInfo[]>;
    query: string;
    /** The grid guide per channel id, read as rows come on screen (Xtream) or from the link's guide. */
    grid: Record<string, GuideProgramme[]>;
    /** A link's guide downloading, with its progress, and how it ended. */
    gridLoad: Busy;
    /** Programmes to be told about, this playlist's. */
    reminders: readonly Reminder[];
    /** A reminder whose programme just started, shown over any screen with Watch. */
    due: Reminder | null;
    /** Today's games, fights and pay-per-views, live first. */
    events: LiveEvent[];
    /** Reading sports channels' guides for them, with progress, and how it ended. */
    eventsLoad: Busy;
    /** Teams, leagues and fighters followed on the phone or the website, from the account's backup. */
    follows: string[];
    tune: Tune | null;
    /** Every playlist on the stick, by name; the open one is `playlistId`. */
    playlists: { id: string; name: string }[];
    playlistId: string;
    /** Adding another playlist from Settings: the sign-in screens offer Back to Settings. */
    adding: boolean;
    /** "Who's watching?": the account's profiles, as the phone and the website made them; none when signed out. */
    profiles: AccountProfiles;
    /** The profile in use; null with fewer than two profiles, or before one is picked. */
    profileId: string | null;
    /** Each profile's own lists, and channels renamed or given a logo on the phone or website. */
    lists: AccountLists;
    /** "Add to list" open for a channel or a movie. */
    listFor: { id: string; name: string } | null;
    /** A change to the account saving (what for: a list's id, "new", "favourite"), and why the last one didn't stick. */
    listSave: { busy: string | null; problem: ListProblem | null };
    /** Trakt and Simkl in Settings: signed in or not, the code being shown, and how the last step ended. */
    trackers: Record<Tracker, TrackerView>;
}

export interface Services {
    store: Store;
    http: Http;
    /** The account and relay calls (`fetch` on the stick). */
    fetch: Fetch;
    random: (n: number) => Uint8Array;
    player: StreamPlayer;
    deviceName: string;
    site?: string;
    /** Waits until the first screen has been drawn before background work starts (QuietStart on Android). */
    quietStart?: () => Promise<void>;
    /** The app's TMDB key, built in at build time (platform/keys.ts); empty turns details off. */
    tmdbKey?: string;
    /** Simkl's and Trakt's app ids (SIMKL_CLIENT_ID, TRAKT_CLIENT_ID), built in the same way; empty hides that one. */
    simklId?: string;
    traktId?: string;
    /** The TV's language for TMDB ("fr-CA"). */
    language?: string;
}

/** The services that keep what's watched, as on Android: each signs in with a code and scrobbles on its own. */
export type Tracker = "trakt" | "simkl";
export const TRACKERS: readonly Tracker[] = ["trakt", "simkl"];

export interface TrackerView {
    signedIn: boolean;
    user: string | null;
    /** Settings' Scrobble what you watch. */
    scrobble: boolean;
    /** Connecting or disconnecting. */
    busy: boolean;
    /** The code to type at simkl.com/pin or trakt.tv/activate while connecting. */
    code: SignInCode | null;
    problem: "offline" | "expired" | "denied" | null;
}

export const NO_TRACKER: TrackerView = { signedIn: false, user: null, scrobble: true, busy: false, code: null, problem: null };
export const NO_TRACKERS: Record<Tracker, TrackerView> = { trakt: NO_TRACKER, simkl: NO_TRACKER };

export class AppModel {
    state: State = {
        route: "boot",
        login: null,
        lineup: null,
        loading: { progress: null, error: null },
        refresh: { busy: false },
        account: null,
        accountCheck: { busy: false },
        category: null,
        channelId: null,
        guide: {},
        library: null,
        libraryLoad: { busy: false },
        movieCategory: null,
        showCategory: null,
        title: null,
        episodes: {},
        episodesLoad: { id: null, busy: false, failed: false },
        vod: null,
        positions: {},
        watching: [],
        prefs: DEFAULT_PREFS,
        favourites: new Set(),
        notice: null,
        details: {},
        detailsLoad: { id: null, busy: false, failed: false },
        seasons: {},
        query: "",
        playlists: [],
        playlistId: FIRST,
        adding: false,
        grid: {},
        gridLoad: { busy: false },
        reminders: [],
        due: null,
        events: [],
        eventsLoad: { busy: false },
        follows: [],
        tune: null,
        profiles: NO_PROFILES,
        profileId: null,
        lists: NO_LISTS,
        listFor: null,
        listSave: { busy: null, problem: null },
        trackers: NO_TRACKERS,
    };
    /** One guard for every PIN on the stick, so switching between a profile's and the parental PIN doesn't reset it. */
    readonly pins = new PinThrottle();
    private readonly trackers: Record<Tracker, Simkl | Trakt>;
    /** Each Connect counts up, so going back stops its polling. */
    private trackerRun: Record<Tracker, number> = { trakt: 0, simkl: 0 };
    /** What Trakt and Simkl were told is playing, until it stops. */
    private scrobbling: ScrobbleMedia | null = null;
    /** Whether "Who's watching?" has been answered since the app opened; it asks at each start, as on Android. */
    private picked = false;
    /** A layer over the screen (the PIN pad) that Back closes first; it says whether it took the key. */
    onBack: (() => boolean) | null = null;
    readonly saved: Storage;
    favourites!: Favourites;
    readonly tmdb: Tmdb;
    progress!: WatchProgress;
    reminders!: Reminders;
    health!: ChannelHealth;
    private tuneTimer: ReturnType<typeof setTimeout> | null = null;
    /** Bumped on each channel auto-tune tries, so a late answer about an earlier one is ignored. */
    private tuneTry = 0;
    private reminderTimer: ReturnType<typeof setTimeout> | null = null;
    /** Channels whose guide is being read, so a row scrolled past twice is asked for once. */
    private gridAsked = new Set<string>();
    private linkGuideRead = false;
    /** Every playlist with its login; only the open one's channels and movies are kept. */
    private saved_playlists: SavedPlaylist[] = [];
    /** Bumped on each switch, so a download for the playlist left behind never lands on the next one. */
    private generation = 0;
    private readonly shared: KeyValueStore;
    private noticeTimer: ReturnType<typeof setTimeout> | null = null;
    /** Where Back from a movie's or show's page goes: the grid it was picked from, or Search. */
    private titleFrom: Route = "movies";
    /** Whether the saved movies and shows have been read yet (after the first screen, so Live TV comes first). */
    private libraryRead = false;
    readonly account: TunedAccount;
    readonly stream: OneStream;
    private listeners = new Set<() => void>();

    constructor(readonly services: Services) {
        this.saved = storage(services.store);
        this.account = new TunedAccount({
            fetch: services.fetch,
            store: sharedStore(services.store),
            crypto: nobleCrypto(services.random),
            gunzip,
            device: { kind: "tv", build: "vega", name: services.deviceName },
            base: services.site ?? ACCOUNT_BASE,
            log: (tag, message) => log.info(tag, message),
        });
        this.stream = new OneStream(services.player);
        const shared = sharedStore(services.store);
        this.tmdb = new Tmdb(services.fetch, shared, services.tmdbKey ?? "", Date.now, services.language ?? "en-CA");
        this.shared = shared;
        const say = (tag: string, message: string) => log.info(tag, message);
        this.trackers = {
            trakt: new Trakt({ fetch: services.fetch, store: shared, clientId: services.traktId ?? "", log: say }),
            simkl: new Simkl({ fetch: services.fetch, store: shared, clientId: services.simklId ?? "", appVersion: "vega", log: say }),
        };
        this.keepFor(FIRST);
    }

    /**
     * Favourites and Continue watching are each playlist's own, under the Samsung app's keys for the same id, and with
     * profiles each profile's own; the account's first profile keeps the ones the stick had before profiles.
     */
    private keepFor(id: string): void {
        this.favourites = new Favourites(this.shared, this.profileKey(favouritesKey(id)));
        this.favourites.onChange(() => this.showFavourites());
        this.progress = new WatchProgress(this.shared, this.profileKey(progressKey(id)));
        this.progress.onChange((list) =>
            this.set({
                positions: Object.fromEntries(list.map((w) => [w.id, w.at])),
                watching: this.progress.continueWatching(),
            }),
        );
        this.progressRead = null;
        this.reminders = new Reminders(this.shared, remindersKey(id));
        this.reminders.onChange((reminders) => this.set({ reminders }));
        this.health = new ChannelHealth(this.shared, healthKey(id));
    }

    private profileKey(key: string): string {
        const id = this.state.profileId;
        return id && id !== this.state.profiles.profiles[0]?.id ? `${key}.${id}` : key;
    }

    subscribe = (listener: () => void) => {
        this.listeners.add(listener);
        return () => void this.listeners.delete(listener);
    };

    getState = () => this.state;

    private set(patch: Partial<State>): void {
        this.state = { ...this.state, ...patch };
        for (const l of this.listeners) {
            try {
                l();
            } catch (error) {
                log.error("AppModel", "listener", error);
            }
        }
    }

    go(route: Route): void {
        this.set({ route });
    }

    /** Opening the app: the saved channels at once, then a refresh once the screen is up if they're old. */
    async boot(): Promise<void> {
        const [login, lineup, account, last] = await Promise.all([
            this.saved.loadLogin().catch(() => null),
            this.saved.loadLineup().catch(() => null),
            this.account.state().then(accountView, () => null),
            this.saved.loadLastChannel().catch(() => null),
        ]);
        rememberSecrets(login);
        if (account) await this.readProfiles();
        this.keepFor(this.state.playlistId);
        await this.readPlaylists(login);
        await Promise.all([this.loadKept(), this.loadPrefs(), this.readTrackers()]);
        if (!login) return this.set({ route: "signin", account });
        if (!lineup) {
            this.set({ login, account, route: "loading" });
            return this.firstDownload();
        }
        this.set({ login, lineup, account, channelId: last ?? lineup.channels[0]?.id ?? null });
        this.set({ route: this.home() });
        launchSafely("boot.background", async () => {
            await this.services.quietStart?.();
            await this.readSavedLibrary();
            if (account) await this.refreshProfiles();
            if (isStale(lineup)) await this.refreshNow();
            else if (!this.state.library || isStale(this.state.library)) await this.refreshLibrary();
            if (account) await this.checkAccount();
        });
    }

    /** Favourites, Continue watching, reminders and channel health for the playlist and profile in use. */
    private loadKept(): Promise<unknown> {
        return Promise.all([this.favourites.load().catch(() => undefined), this.readProgress(), this.readReminders(), this.health.load()]);
    }

    /** Where opening the app lands: "Who's watching?" while there are profiles to pick from, else Live TV. */
    private home(): Route {
        return this.state.profiles.profiles.length > 1 && !this.picked ? "profiles" : "live";
    }

    /** The playlists kept on the stick; a stick from before there could be several has just its one login. */
    private async readPlaylists(login: Login | null): Promise<void> {
        const [list, id] = await Promise.all([this.saved.loadPlaylists().catch(() => []), this.saved.loadPlaylistId().catch(() => null)]);
        this.saved_playlists = list.length ? list : login ? [{ id: FIRST, login }] : [];
        for (const p of this.saved_playlists) rememberSecrets(p.login);
        const open = id && this.saved_playlists.some((p) => p.id === id) ? id : (this.saved_playlists[0]?.id ?? FIRST);
        if (open !== this.state.playlistId) this.keepFor(open);
        this.showPlaylists(open);
    }

    private showPlaylists(playlistId: string): void {
        this.set({ playlists: this.saved_playlists.map((p) => ({ id: p.id, name: playlistName(p.login) })), playlistId });
    }

    /**
     * A login from the phone, the form or the account's backup: one already on the stick opens, a new one is added and
     * opens (with any others sent alongside added too), then its first download. The first ever is "current".
     */
    async useLogin(login: Login, others: Login[] = []): Promise<void> {
        rememberSecrets(login);
        for (const o of others) rememberSecrets(o);
        const add = (l: Login): string => {
            const found = this.saved_playlists.find((p) => sameLogin(p.login, l));
            if (found) {
                found.login = l;
                return found.id;
            }
            const taken = new Set(this.saved_playlists.map((p) => p.id));
            let id = taken.has(FIRST) ? `p${Date.now().toString(36)}` : FIRST;
            for (let n = 2; taken.has(id); n++) id = `p${Date.now().toString(36)}${n}`;
            this.saved_playlists.push({ id, login: l });
            return id;
        };
        const id = add(login);
        // More than one playlist is Premium (premium.md); without it, only the one picked is kept.
        if (hasPremium(this.state.account)) for (const o of others) add(o);
        await this.saved.savePlaylists(this.saved_playlists);
        await this.open(id, login);
    }

    /** Settings › Playlists: another one on the stick. Its channels download as on the first sign-in. */
    async switchPlaylist(id: string): Promise<void> {
        const target = this.saved_playlists.find((p) => p.id === id);
        if (!target || (id === this.state.playlistId && this.state.lineup)) return;
        await this.open(id, target.login);
    }

    private async open(id: string, login: Login): Promise<void> {
        const switching = id !== this.state.playlistId;
        await this.stream.stop();
        if (switching) {
            this.generation++;
            await this.saved.clearDownloads().catch((e) => log.warn("AppModel", "clear downloads", e));
            this.keepFor(id);
            this.libraryRead = false;
        }
        await this.saved.saveLogin(login);
        await this.saved.savePlaylistId(id);
        this.showPlaylists(id);
        await this.loadKept();
        this.gridAsked.clear();
        this.linkGuideRead = false;
        this.endTune();
        this.set({
            grid: {},
            gridLoad: { busy: false },
            events: [],
            eventsLoad: { busy: false },
            due: null,
            login,
            lineup: null,
            library: null,
            episodes: {},
            guide: {},
            channelId: null,
            category: null,
            movieCategory: null,
            showCategory: null,
            title: null,
            vod: null,
            query: "",
            refresh: { busy: false },
            libraryLoad: { busy: false },
            adding: false,
            route: "loading",
        });
        await this.firstDownload();
    }

    /** Settings › Playlists › Add a playlist: the phone or the form, with Back to Settings. */
    startAdding(): void {
        this.set({ adding: true, route: "signin" });
    }

    cancelAdding(): void {
        this.set({ adding: false, route: this.state.login ? (this.state.lineup ? "settings" : "loading") : "signin" });
    }

    /** Takes a playlist off the stick (never the open one), with its favourites and Continue watching. */
    async removePlaylist(id: string): Promise<void> {
        if (id === this.state.playlistId || !this.saved_playlists.some((p) => p.id === id)) return;
        this.saved_playlists = this.saved_playlists.filter((p) => p.id !== id);
        await this.saved.savePlaylists(this.saved_playlists);
        await Promise.all([this.shared.remove(favouritesKey(id)), this.shared.remove(progressKey(id))]).catch((e) =>
            log.warn("AppModel", "remove playlist", e),
        );
        this.showPlaylists(this.state.playlistId);
    }

    async firstDownload(): Promise<void> {
        const login = this.state.login;
        if (!login) return this.go("signin");
        const generation = this.generation;
        this.set({ loading: { progress: null, error: null } });
        try {
            const { library, ...lineup } = await downloadLineup(this.services.http, login, (progress) => {
                if (generation === this.generation) this.set({ loading: { progress, error: null } });
            });
            if (generation !== this.generation) return;
            await this.saved.saveLineup(lineup);
            if (library) await this.keepLibrary(library);
            this.libraryRead = true;
            this.set({
                lineup,
                route: this.home(),
                category: null,
                channelId: lineup.channels[0]?.id ?? null,
                loading: { progress: null, error: null },
            });
            launchSafely("first.favourites", () => this.seedFavourites());
            if (login.kind === "xtream") launchSafely("first.library", () => this.refreshLibrary());
        } catch (error) {
            log.warn("AppModel", "first download", error);
            if (generation !== this.generation) return;
            this.set({ loading: { progress: null, error: failureText(error) } });
        }
    }

    /** Settings › Refresh channels now, and the background refresh: progress while it runs, then how it ended. */
    async refreshNow(): Promise<void> {
        const login = this.state.login;
        if (!login || this.state.refresh.busy) return;
        const generation = this.generation;
        this.set({ refresh: { busy: true, progress: null, result: null } });
        try {
            const { library, ...lineup } = await downloadLineup(this.services.http, login, (progress) => {
                if (generation === this.generation) this.set({ refresh: { busy: true, progress } });
            });
            if (generation !== this.generation) return;
            await this.saved.saveLineup(lineup);
            if (library) await this.keepLibrary(library);
            this.set({ lineup, refresh: { busy: false, result: { ok: true, text: t.refreshed } } });
        } catch (error) {
            log.warn("AppModel", "refresh", error);
            if (generation !== this.generation) return;
            this.set({ refresh: { busy: false, result: { ok: false, text: failureText(error) } } });
            return;
        }
        if (login.kind === "xtream") await this.refreshLibrary();
    }

    private async keepLibrary(library: Library): Promise<void> {
        await this.saved.saveLibrary(library);
        this.set({ library, libraryLoad: { busy: false, result: { ok: true, text: t.refreshedLibrary } } });
        await this.seedFavourites();
    }

    private async readSavedLibrary(): Promise<void> {
        if (this.libraryRead) return;
        this.libraryRead = true;
        const library = await this.saved.loadLibrary().catch(() => null);
        this.set({ library: this.state.library ?? library });
        await this.seedFavourites();
    }

    private progressRead: Promise<void> | null = null;

    /** Where movies and episodes were left, read once (small, so it comes with the first screen). */
    private readProgress(): Promise<void> {
        this.progressRead ??= (async () => {
            const old = await this.saved.loadPositions().catch(() => ({}));
            await this.progress.load();
            if (this.profileKey("") !== "") return;
            // Positions kept before Continue Watching knew each one's title: a movie's id is its own title.
            await this.progress.adopt(old, (id) => (id.startsWith("m") ? id : null)).catch((e) => log.warn("AppModel", "adopt", e));
        })();
        return this.progressRead;
    }

    private async loadPrefs(): Promise<void> {
        try {
            const raw = await this.shared.get(PREFS_KEY);
            const saved = raw ? (JSON.parse(raw) as Partial<Prefs>) : {};
            this.set({
                prefs: {
                    hideAdult: typeof saved.hideAdult === "boolean" ? saved.hideAdult : DEFAULT_PREFS.hideAdult,
                    hide247: typeof saved.hide247 === "boolean" ? saved.hide247 : DEFAULT_PREFS.hide247,
                    sortMovies: isSortOrder(saved.sortMovies) ? saved.sortMovies : DEFAULT_PREFS.sortMovies,
                    sortShows: isSortOrder(saved.sortShows) ? saved.sortShows : DEFAULT_PREFS.sortShows,
                    liveGrid: typeof saved.liveGrid === "boolean" ? saved.liveGrid : DEFAULT_PREFS.liveGrid,
                    tuneWaitS: TUNE_WAITS.includes(saved.tuneWaitS as never) ? (saved.tuneWaitS as number) : DEFAULT_PREFS.tuneWaitS,
                },
            });
        } catch (error) {
            log.warn("AppModel", "settings", error);
        }
    }

    /** Settings › Content and the tabs' Sort: on screen at once, then kept. */
    async setPrefs(patch: Partial<Prefs>): Promise<void> {
        const prefs = { ...this.state.prefs, ...patch };
        const category = (kind: "movies" | "shows") => (kind === "movies" ? this.state.movieCategory : this.state.showCategory);
        this.set({ prefs });
        // A category that just got hidden can't stay picked.
        if (this.state.category && !this.shownCategories("live").some((c) => c.id === this.state.category)) this.set({ category: null });
        for (const kind of ["movies", "shows"] as const) {
            const id = category(kind);
            if (id && id !== FAVOURITES && id !== CONTINUE && !this.shownCategories(kind).some((c) => c.id === id)) {
                this.pickBrowseCategory(kind, null);
            }
        }
        await this.shared.set(PREFS_KEY, JSON.stringify(prefs)).catch((e) => log.warn("AppModel", "save settings", e));
    }

    /** The tab's Sort button: the next order (Playlist order, Recently added, Newest, Top rated, A to Z). */
    async nextSort(kind: "movies" | "shows"): Promise<void> {
        const key = kind === "movies" ? "sortMovies" : "sortShows";
        await this.setPrefs({ [key]: nextSort(this.state.prefs[key]) });
    }

    /** A tab's categories without the hidden ones (adult, 24/7). */
    shownCategories(kind: "live" | "movies" | "shows"): Category[] {
        const list =
            kind === "live"
                ? (this.state.lineup?.categories ?? [])
                : kind === "movies"
                  ? (this.state.library?.movieCategories ?? [])
                  : (this.state.library?.showCategories ?? []);
        const shown = visible(
            list,
            this.kids() ? { ...this.state.prefs, hideAdult: true } : this.state.prefs,
            (c) => c.name,
            () => "",
        );
        if (!this.kids()) return shown;
        // On a kids profile, only the categories something it may watch is in.
        const items = kind === "live" ? this.shownChannels() : this.shownTitles(kind);
        const used = new Set(items.map((x) => x.categoryId));
        return shown.filter((c) => used.has(c.id));
    }

    /** Channels, movies or shows without the hidden ones, by their category's name or their own. */
    private shown<T extends { name: string; categoryId: string }>(
        items: readonly T[],
        categories: readonly Category[],
        live: boolean,
    ): T[] {
        const names = new Map(categories.map((c) => [c.id, c.name]));
        const kids = this.kids();
        const out = visible(
            items,
            kids ? { ...this.state.prefs, hideAdult: true } : this.state.prefs,
            (x) => x.name,
            (x) => names.get(x.categoryId) ?? "",
        );
        // A kids profile sees kids and family only (`KidsContent`), as on Android.
        const allowed = kids ? out.filter((x) => isKids(names.get(x.categoryId) ?? "", x.name, live)) : out;
        // Channels renamed or given a logo on the phone or website show that way here too (lists.md).
        const edits = this.state.lists.edits;
        if (!live || !Object.keys(edits).length) return allowed;
        return allowed.map((x) => {
            const c = x as unknown as Channel;
            const edit = edits[hexToId(androidIdHex("live", c.url, c.name))];
            if (!edit) return x;
            return { ...c, name: edit.name ?? c.name, logo: edit.logo ?? c.logo, was: c.name } as unknown as T;
        });
    }

    /** Whether the profile in use is a kids one. */
    kids(): boolean {
        return this.currentProfile()?.kids === true;
    }

    /** Every channel that isn't hidden. */
    shownChannels(): Channel[] {
        const lineup = this.state.lineup;
        return lineup ? this.shownMemo("live", lineup.channels, lineup.categories) : [];
    }

    /** Every movie or show that isn't hidden, in the playlist's order. */
    shownTitles(kind: "movies" | "shows"): Title[] {
        const library = this.state.library;
        if (!library) return [];
        return kind === "movies"
            ? this.shownMemo("movies", library.movies, library.movieCategories)
            : this.shownMemo("shows", library.shows, library.showCategories);
    }

    private memo = new Map<string, { items: readonly unknown[]; prefs: Prefs; kids: boolean; edits: unknown; out: unknown[] }>();

    /** Filtering a big list once per change of list or setting, not on every screen draw. */
    private shownMemo<T extends { name: string; categoryId: string }>(
        key: string,
        items: readonly T[],
        categories: readonly Category[],
    ): T[] {
        const hit = this.memo.get(key);
        if (
            hit &&
            hit.items === items &&
            hit.prefs.hideAdult === this.state.prefs.hideAdult &&
            hit.prefs.hide247 === this.state.prefs.hide247 &&
            hit.kids === this.kids() &&
            hit.edits === this.state.lists.edits
        ) {
            return hit.out as T[];
        }
        const out = this.shown(items, categories, key === "live");
        this.memo.set(key, { items, prefs: this.state.prefs, kids: this.kids(), edits: this.state.lists.edits, out });
        return out;
    }

    /** Continue Watching for a tab: its titles, newest watched first. */
    continueTitles(kind: "movies" | "shows"): Title[] {
        const byId = new Map(this.shownTitles(kind).map((x) => [x.id, x]));
        return this.state.watching.flatMap((w) => byId.get(w.titleId) ?? []);
    }

    /**
     * Movies and shows: an Xtream server's in four steps of their own, a link's with its channels (the same file).
     * Progress while it runs, then how it ended; the saved ones stay on screen if it fails.
     */
    async refreshLibrary(): Promise<void> {
        const login = this.state.login;
        if (!login || this.state.libraryLoad.busy) return;
        if (login.kind !== "xtream") return this.refreshNow();
        const generation = this.generation;
        this.set({ libraryLoad: { busy: true, progress: null, result: null } });
        try {
            const library = await downloadLibrary(this.services.http, login, (progress) => {
                if (generation === this.generation) this.set({ libraryLoad: { busy: true, progress } });
            });
            if (generation !== this.generation) return;
            await this.keepLibrary(library);
        } catch (error) {
            log.warn("AppModel", "library", error);
            if (generation !== this.generation) return;
            this.set({ libraryLoad: { busy: false, result: { ok: false, text: failureText(error) } } });
        }
    }

    /** Movies or Shows on the remote: what the stick saved at once, and a download only when it has none. */
    async openBrowse(kind: "movies" | "shows"): Promise<void> {
        this.go(kind);
        await this.readSavedLibrary();
        if (!this.state.library && !this.state.libraryLoad.busy && !this.state.refresh.busy) await this.refreshLibrary();
    }

    pickBrowseCategory(kind: "movies" | "shows", id: string | null): void {
        this.set(kind === "movies" ? { movieCategory: id } : { showCategory: id });
    }

    /** What a tab's grid shows: the picked category, sorted as the tab's Sort says (Continue Watching stays newest first). */
    visibleTitles(kind: "movies" | "shows"): Title[] {
        const category = kind === "movies" ? this.state.movieCategory : this.state.showCategory;
        if (category === CONTINUE) return this.continueTitles(kind);
        const all = this.shownTitles(kind);
        const list = category?.startsWith(LIST) ? this.myLists().find((l) => LIST + l.id === category) : null;
        if (list) return this.inList(list, all);
        const picked =
            category === FAVOURITES
                ? all.filter((t) => this.state.favourites.has(t.id))
                : category
                  ? all.filter((t) => t.categoryId === category)
                  : all;
        const order = kind === "movies" ? this.state.prefs.sortMovies : this.state.prefs.sortShows;
        // Top rated uses the rating the poster shows: TMDB's once read, else the provider's.
        return sortItems(picked, order, (t) => this.state.details[t.id]?.rating);
    }

    /** Stars or unstars a channel, movie or show, and says which for a few seconds. */
    async toggleFavourite(id: string, name: string): Promise<void> {
        const profile = this.currentProfile();
        const android = profile && this.state.account ? this.androidIdOf(id) : null;
        if (!profile || !android) {
            const on = await this.favourites.toggle(id);
            return this.say({ on, name });
        }
        // Favourites are the account's: the screen changes at once, and goes back with a note if it didn't stick.
        const on = !this.state.favourites.has(id);
        this.accountFavs = new Set(this.accountFavs);
        if (on) this.accountFavs.add(id);
        else this.accountFavs.delete(id);
        this.showFavourites(false);
        this.say({ on, name });
        const failed = await this.changeAccount("favourite", { op: "favourite", profileId: profile.id, itemId: android, on });
        if (!failed) return;
        this.accountFavs = new Set(this.accountFavs);
        if (on) this.accountFavs.delete(id);
        else this.accountFavs.add(id);
        this.showFavourites(false);
        this.say({ on: !on, name, text: t.listProblem[failed] });
    }

    /** A short line the screens show and take away themselves. */
    private say(notice: NonNullable<State["notice"]>): void {
        if (this.noticeTimer) clearTimeout(this.noticeTimer);
        this.set({ notice });
        this.noticeTimer = setTimeout(() => this.set({ notice: null }), 3000);
    }

    /** Search reads the saved movies and shows too, so it finds them before Movies has been opened. */
    async openSearch(): Promise<void> {
        this.go("search");
        await this.readSavedLibrary();
    }

    /** Search across channels, movies and shows, channels first, as Android's search lists them. */
    setQuery(query: string): void {
        this.set({ query });
    }

    results(): Found[] {
        const q = this.state.query;
        if (!q.trim()) return [];
        // Hidden channels, movies and shows stay out of search too, as on Android.
        const channels = searchItems(this.shownChannels(), q, (c) => (c.was ? `${c.name} ${c.was}` : c.name), 200);
        const movies = searchItems(this.shownTitles("movies"), q, (t) => t.name, 200);
        const shows = searchItems(this.shownTitles("shows"), q, (t) => t.name, 200);
        return [
            ...channels.map((channel) => ({ kind: "live" as const, channel })),
            ...movies.map((title) => ({ kind: "movie" as const, title })),
            ...shows.map((title) => ({ kind: "show" as const, title })),
        ];
    }

    /**
     * TMDB's details for a page (Melvin, 2026-11-17: real names over numbers): read once, kept on the stick, and for a
     * show each season's episode names. The playlist's own details show meanwhile, and stay if TMDB can't be reached.
     */
    async loadDetails(title: Title): Promise<void> {
        if (!this.tmdb.enabled || title.id in this.state.details) return this.loadSeasons(title);
        this.set({ detailsLoad: { id: title.id, busy: true, failed: false } });
        try {
            const details = await this.tmdb.details(title.kind, title.name, title.year);
            this.set({
                details: { ...this.state.details, [title.id]: details },
                detailsLoad: { id: title.id, busy: false, failed: false },
            });
        } catch (error) {
            log.warn("AppModel", "details", error);
            this.set({ detailsLoad: { id: title.id, busy: false, failed: true } });
            return;
        }
        await this.loadSeasons(title);
    }

    /** TMDB's names for the seasons a show's episodes are in. */
    private async loadSeasons(title: Title): Promise<void> {
        const details = this.state.details[title.id];
        const episodes = this.episodesOf(title);
        if (title.kind !== "show" || !details || !episodes) return;
        for (const season of [...new Set(episodes.map((e) => e.season))]) {
            const key = `${details.tmdbId}:${season}`;
            if (this.state.seasons[key]) continue;
            try {
                const list = await this.tmdb.season(details.tmdbId, season);
                this.set({ seasons: { ...this.state.seasons, [key]: list } });
            } catch (error) {
                log.warn("AppModel", "season names", error);
            }
        }
    }

    /** An episode's name: TMDB's where it has one, else the provider's, else null (the screen says "Episode 2"). */
    episodeName(title: Title, episode: Episode): string | null {
        const details = this.state.details[title.id];
        const tmdb = details
            ? this.state.seasons[`${details.tmdbId}:${episode.season}`]?.find((e) => e.episode === episode.episode)
            : undefined;
        return tmdb?.name ?? episode.name;
    }

    /** A movie's or show's page; an Xtream show reads its episodes as it opens (one small request). */
    async openTitle(title: Title): Promise<void> {
        if (this.state.route !== "title" && this.state.route !== "player") this.titleFrom = this.state.route;
        this.set({ title, route: "title" });
        launchSafely("title.details", () => this.loadDetails(title));
        if (title.kind === "show" && !title.episodes && !this.state.episodes[title.id]) await this.loadEpisodes(title);
        if (title.kind === "show") await this.loadSeasons(title);
    }

    closeTitle(): void {
        const kind = this.state.title?.kind === "show" ? "shows" : "movies";
        this.go(this.titleFrom === "search" ? "search" : kind);
    }

    episodesOf(title: Title): Episode[] | null {
        return title.episodes ?? this.state.episodes[title.id] ?? null;
    }

    async loadEpisodes(title: Title): Promise<void> {
        const login = this.state.login;
        if (!login || login.kind !== "xtream" || title.seriesId === undefined) return;
        this.set({ episodesLoad: { id: title.id, busy: true, failed: false } });
        try {
            const answer = await this.services.http.getJson(seriesInfoUrl(login, title.seriesId));
            if (answer.status < 200 || answer.status >= 300) throw new Error(`HTTP ${answer.status}`);
            const episodes = parseEpisodes(answer.body, login, title.name);
            this.set({
                episodes: { ...this.state.episodes, [title.id]: episodes },
                episodesLoad: { id: title.id, busy: false, failed: false },
            });
        } catch (error) {
            log.warn("AppModel", "episodes", error);
            this.set({ episodesLoad: { id: title.id, busy: false, failed: true } });
        }
    }

    /** A movie or an episode, from `from` seconds: the old stream closes before this one opens. */
    async playVod(item: Playable, from = 0): Promise<void> {
        this.scrobbleEnd();
        this.endTune();
        this.set({ vod: item, route: "player" });
        await this.stream.play(item.url, from > 0 ? from : undefined);
    }

    /** Keeps where the movie or episode is; one near its end counts as watched and starts over next time. */
    async rememberPosition(): Promise<void> {
        const vod = this.state.vod;
        const where = this.services.player.position?.();
        if (!vod || vod.replay || !where || where.at < 5) return;
        // Near its end it counts as watched, leaves Continue Watching and starts over next time.
        await this.progress.save(vod.id, vod.titleId ?? this.state.title?.id ?? vod.id, where.at, where.length);
    }

    pickCategory(id: string | null): void {
        this.set({ category: id });
    }

    visibleChannels(): Channel[] {
        const { category } = this.state;
        const all = this.shownChannels();
        if (category === FAVOURITES) return all.filter((c) => this.state.favourites.has(c.id));
        if (category?.startsWith(LIST)) {
            const list = this.myLists().find((l) => LIST + l.id === category);
            return list ? this.inList(list, all) : [];
        }
        return category ? all.filter((c) => c.categoryId === category) : all;
    }

    /** Plays a channel: the old stream closes before this one opens. */
    async play(channel: Channel): Promise<void> {
        this.scrobbleEnd();
        this.endTune();
        this.set({ channelId: channel.id, vod: null, route: "player" });
        launchSafely("play.remember", () => this.saved.saveLastChannel(channel.id));
        await this.stream.play(channel.url);
        launchSafely("play.guide", () => this.loadGuide(channel));
    }

    /** Up and down in the player: the next or previous channel in the list being shown, wrapping round. */
    async zap(step: 1 | -1): Promise<void> {
        const tune = this.state.tune;
        // While auto-tune has a list, up and down move through it, as Android's next and previous do.
        if (tune && !this.state.vod) return this.tryTune((tune.at + step + tune.ids.length) % tune.ids.length);
        const list = this.visibleChannels();
        if (!list.length || this.state.vod) return;
        const at = Math.max(
            0,
            list.findIndex((c) => c.id === this.state.channelId),
        );
        await this.play(list[(at + step + list.length) % list.length]);
    }

    async stopPlaying(): Promise<void> {
        const vod = this.state.vod;
        this.scrobbleEnd();
        if (vod) await this.rememberPosition().catch((error) => log.warn("AppModel", "position", error));
        const tuned = !!this.state.tune;
        this.endTune();
        await this.stream.stop();
        this.set({ route: vod && this.state.title ? "title" : tuned ? "events" : "live", vod: null });
    }

    /** Now and next for Xtream channels: one small request, kept for the session. */
    private async loadGuide(channel: Channel): Promise<void> {
        const login = this.state.login;
        if (!login || login.kind !== "xtream" || channel.streamId === undefined || this.state.guide[channel.id]) return;
        const answer = await this.services.http.getJson(shortEpgUrl(login, channel.streamId));
        const now = Date.now();
        const programmes = parseShortEpg(answer.body).filter((p) => p.end > now);
        this.set({ guide: { ...this.state.guide, [channel.id]: programmes } });
    }

    // ---- The grid guide, catch-up and reminders (docs/decisions/vega.md) ----

    /**
     * The guide for the rows on screen and a few either side. An Xtream server's comes a channel at a time
     * (`get_simple_data_table`, a small answer with its recordings), two at once, so the stick never holds the whole
     * guide; a link's comes from its guide file, once a session.
     */
    async loadGridRows(channels: readonly Channel[]): Promise<void> {
        const login = this.state.login;
        if (!login) return;
        if (login.kind !== "xtream") return this.loadLinkGuide();
        const wanted = channels.filter((c) => c.streamId !== undefined && !this.gridAsked.has(c.id) && !(c.id in this.state.grid));
        for (const c of wanted) this.gridAsked.add(c.id);
        const generation = this.generation;
        const next = async (): Promise<void> => {
            const channel = wanted.shift();
            if (!channel || generation !== this.generation) return;
            // A row that can't be read stays "…" and is asked for again when it next comes on screen.
            let programmes: GuideProgramme[] | null = null;
            try {
                const answer = await this.services.http.getJson(epgTableUrl(login, channel.streamId!));
                if (answer.status >= 200 && answer.status < 300) programmes = this.window(parseEpgTable(answer.body), channel);
            } catch (error) {
                log.warn("AppModel", "guide row", error);
            }
            if (generation !== this.generation) return;
            if (programmes) this.set({ grid: { ...this.state.grid, [channel.id]: programmes } });
            else this.gridAsked.delete(channel.id);
            await next();
        };
        await Promise.all([next(), next()]);
    }

    /** What the grid keeps of a channel's guide: from as far back as it keeps recordings (or 2 hours) to 2 days ahead. */
    private window(list: GuideProgramme[], channel: Channel, now = Date.now()): GuideProgramme[] {
        const from = Math.min(now - 2 * 3600_000, keepFrom(now, channel.catchUp?.days ?? 0));
        const to = now + GUIDE_DAYS * 24 * 3600_000;
        return list.filter((p) => p.stop > from && p.start < to);
    }

    /** A link's guide (the login's, or the one its `#EXTM3U` line names), with progress, then how it ended. */
    async loadLinkGuide(force = false): Promise<void> {
        const login = this.state.login;
        const lineup = this.state.lineup;
        if (!login || login.kind !== "link" || !lineup || this.state.gridLoad.busy || (this.linkGuideRead && !force)) return;
        const url = login.guideUrl ?? lineup.guideUrl;
        this.linkGuideRead = true;
        if (!url) return;
        const generation = this.generation;
        const byGuideId = new Map<string, Channel[]>();
        for (const c of lineup.channels) if (c.guideId) byGuideId.set(c.guideId, [...(byGuideId.get(c.guideId) ?? []), c]);
        const now = Date.now();
        const longest = lineup.channels.reduce((m, c) => Math.max(m, c.catchUp?.days ?? 0), 0);
        const from = Math.min(now - 2 * 3600_000, keepFrom(now, Math.min(longest, MAX_CATCH_UP_DAYS)));
        const to = now + GUIDE_DAYS * 24 * 3600_000;
        const label = t.gettingGuide;
        const startedAt = Date.now();
        this.set({ gridLoad: { busy: true, progress: { step: 1, steps: 1, label }, result: null } });
        try {
            const text = await this.services.http.text(url, {
                timeoutMs: 180_000,
                onProgress: ({ loaded, total }) => {
                    if (generation === this.generation) {
                        this.set({
                            gridLoad: {
                                busy: true,
                                progress: { step: 1, steps: 1, label, loaded, total, eta: eta(loaded, total, startedAt, Date.now()) },
                            },
                        });
                    }
                },
            });
            if (generation !== this.generation) return;
            const reader = new XmltvReader((channel, start, stop) => byGuideId.has(channel) && stop > from && start < to);
            const grid: Record<string, GuideProgramme[]> = {};
            const take = (piece: ReturnType<XmltvReader["feed"]>) => {
                for (const p of piece.programmes) {
                    for (const c of byGuideId.get(p.channel) ?? []) {
                        (grid[c.id] ??= []).push({ start: p.start, stop: p.stop, title: p.title, desc: p.desc });
                    }
                }
            };
            for (let at = 0; at < text.length; at += 256 * 1024) {
                take(reader.feed(text.slice(at, at + 256 * 1024)));
                await new Promise<void>((resolve) => setTimeout(resolve, 0));
                if (generation !== this.generation) return;
            }
            take(reader.end());
            for (const list of Object.values(grid)) list.sort((a, b) => a.start - b.start);
            for (const c of lineup.channels) grid[c.id] ??= [];
            this.set({ grid, gridLoad: { busy: false, result: { ok: true, text: t.refreshed } } });
        } catch (error) {
            log.warn("AppModel", "link guide", error);
            if (generation !== this.generation) return;
            this.linkGuideRead = false;
            this.set({ gridLoad: { busy: false, result: { ok: false, text: t.guideFailed } } });
        }
    }

    /**
     * A past programme on a channel that keeps recordings, from its start (catch-up), through the same one connection.
     * False, with nothing playing, when there's no link to be made.
     */
    async playReplay(channel: Channel, programme: GuideProgramme): Promise<boolean> {
        const now = Date.now();
        if (!channel.catchUp || !replayable(programme.start, now, channel.catchUp.days)) return false;
        const url = catchUpLink(channel.url, channel.catchUp, programme.start, programme.stop, now, this.state.lineup?.timezone ?? null);
        if (!url) return false;
        this.set({ title: null, channelId: channel.id });
        await this.playVod({ id: `r:${channel.id}:${programme.start}`, title: programme.title, subtitle: channel.name, url, replay: true });
        return true;
    }

    private async readReminders(): Promise<void> {
        await this.reminders.load().catch((e) => log.warn("AppModel", "reminders", e));
        this.scheduleReminders();
    }

    /** Sets or clears a reminder for a programme that hasn't started, and says which. */
    async toggleReminder(channel: Channel, programme: GuideProgramme): Promise<void> {
        const on = await this.reminders.toggle({
            channelId: channel.id,
            channelName: channel.name,
            title: programme.title,
            start: programme.start,
            stop: programme.stop,
        });
        this.say({ on, name: programme.title, text: on ? t.reminderAdded(programme.title) : t.reminderRemoved(programme.title) });
        this.scheduleReminders();
    }

    /** Waits for the next reminder (at most an hour at a time), then shows it over whatever is on screen. */
    private scheduleReminders(): void {
        if (this.reminderTimer) clearTimeout(this.reminderTimer);
        this.reminderTimer = null;
        const next = this.reminders.next();
        if (next === null) return;
        const wait = Math.max(1000, Math.min(next - Date.now(), 3600_000));
        this.reminderTimer = setTimeout(() => {
            launchSafely("reminders", async () => {
                const due = await this.reminders.due();
                if (due.length) this.set({ due: due[due.length - 1] });
                this.scheduleReminders();
            });
        }, wait);
    }

    /** Watch on a reminder: its channel, through the one connection. */
    async watchDue(): Promise<void> {
        const due = this.state.due;
        this.set({ due: null });
        if (due?.tune) return this.tuneEvent(due.title, [due.channelId]);
        const channel = due && this.state.lineup?.channels.find((c) => c.id === due.channelId);
        if (channel) await this.play(channel);
    }

    dismissDue(): void {
        this.set({ due: null });
    }

    // ---- Live events and auto-tune (docs/decisions/events.md, auto-tune.md, vega.md) ----

    /** Sports and pay-per-view channels that aren't hidden. */
    sportsChannels(): Channel[] {
        const names = new Map((this.state.lineup?.categories ?? []).map((c) => [c.id, c.name]));
        return this.shownChannels().filter((c) => sportyChannel(c.name, names.get(c.categoryId) ?? ""));
    }

    /** Live events: the list from what the stick has at once, then the sports channels' guides as they come. */
    async openEvents(): Promise<void> {
        this.set({ route: "events" });
        this.buildEvents();
        await this.loadEventGuide();
    }

    /**
     * The events from the guide read so far, and from the names of sports channels whose guide lists none in the next
     * 36 hours (pay-per-view slots renamed for each event).
     */
    buildEvents(now = Date.now()): void {
        const sports = this.sportsChannels();
        const to = now + EVENTS_AHEAD_MS;
        const rows: EventProgramme[] = [];
        for (const c of sports) {
            for (const p of this.state.grid[c.id] ?? []) {
                if (p.stop > now && p.start < to) rows.push({ channelId: c.id, title: p.title, start: p.start, stop: p.stop });
            }
        }
        const fromGuide = groupEvents(rows, now);
        const covered = new Set(fromGuide.flatMap((e) => e.channels));
        let named: ReturnType<typeof eventsInNames> = [];
        try {
            named = eventsInNames(
                sports.filter((c) => !covered.has(c.id)),
                now,
                to,
            );
        } catch (error) {
            // A name the rules trip over leaves the guide's events as they are.
            log.warn("AppModel", "event names", error);
        }
        this.set({ events: withNamed(fromGuide, named, now) });
    }

    /**
     * The sports channels' guides. An Xtream server's come a channel at a time, two at once, at most 300 channels, with
     * how many are read so far; a link's is its guide file, read once a session for the grid as well.
     */
    async loadEventGuide(): Promise<void> {
        const login = this.state.login;
        if (!login || this.state.eventsLoad.busy) return;
        if (login.kind !== "xtream") {
            await this.loadLinkGuide();
            this.buildEvents();
            return;
        }
        const wanted = this.sportsChannels()
            .filter((c) => c.streamId !== undefined && !(c.id in this.state.grid))
            .slice(0, MAX_EVENT_ROWS);
        if (!wanted.length) return;
        const generation = this.generation;
        const total = wanted.length;
        const startedAt = Date.now();
        let done = 0;
        let failed = 0;
        const show = () =>
            this.set({
                eventsLoad: {
                    busy: true,
                    progress: {
                        step: 1,
                        steps: 1,
                        label: t.eventsGuide(done, total),
                        loaded: done,
                        total,
                        eta: eta(done, total, startedAt, Date.now()),
                    },
                },
            });
        show();
        const queue = [...wanted];
        const next = async (): Promise<void> => {
            const channel = queue.shift();
            if (!channel || generation !== this.generation) return;
            this.gridAsked.add(channel.id);
            let programmes: GuideProgramme[] | null = null;
            try {
                const answer = await this.services.http.getJson(epgTableUrl(login, channel.streamId!));
                if (answer.status >= 200 && answer.status < 300) programmes = this.window(parseEpgTable(answer.body), channel);
            } catch (error) {
                log.warn("AppModel", "event guide", error);
            }
            if (generation !== this.generation) return;
            done++;
            if (programmes) this.set({ grid: { ...this.state.grid, [channel.id]: programmes } });
            else {
                failed++;
                this.gridAsked.delete(channel.id);
            }
            show();
            if (done % 10 === 0) this.buildEvents();
            await next();
        };
        await Promise.all([next(), next()]);
        if (generation !== this.generation) return;
        this.buildEvents();
        const ok = failed < total;
        this.set({ eventsLoad: { busy: false, result: { ok, text: ok ? t.refreshed : t.guideFailed } } });
    }

    /**
     * Tune: the event's channels and every other one whose name, or whose guide now or within the hour, has its words,
     * best first (English, known-good, listed, sharper), tried one at a time through the one connection.
     */
    async tuneEvent(title: string, channelIds: readonly string[] = []): Promise<void> {
        const now = Date.now();
        const words = tuneWords(title);
        const listed = new Set(channelIds);
        const names = new Map((this.state.lineup?.categories ?? []).map((c) => [c.id, c.name]));
        const soon = now + 3600_000;
        const candidates: TuneCandidate[] = [];
        for (const c of this.shownChannels()) {
            const nameMatch = hasWords(c.name, words);
            const guideMatch =
                listed.has(c.id) || (this.state.grid[c.id] ?? []).some((p) => p.start < soon && p.stop > now && hasWords(p.title, words));
            if (!nameMatch && !guideMatch) continue;
            const group = names.get(c.categoryId) ?? "";
            candidates.push({
                id: c.id,
                name: c.name,
                group,
                region: regionTag(c.name, group),
                nameMatch,
                guideMatch,
                ...this.health.of(c.id),
            });
        }
        const ids = rankForTune(candidates, now).map((c) => c.id);
        if (!ids.length) return this.say({ on: false, name: title, text: t.tuneNothing(title) });
        this.set({ tune: { title, ids, at: 0, found: false, failed: false } });
        await this.tryTune(0);
    }

    /** Tries the `at`th channel; past the last, says none played. */
    private async tryTune(at: number): Promise<void> {
        const tune = this.state.tune;
        if (!tune) return;
        this.clearTuneTimer();
        const mine = ++this.tuneTry;
        if (at >= tune.ids.length) {
            await this.stream.stop();
            if (mine === this.tuneTry) this.set({ tune: { ...tune, found: false, failed: true } });
            return;
        }
        const channel = this.state.lineup?.channels.find((c) => c.id === tune.ids[at]);
        if (!channel) return this.tryTune(at + 1);
        this.set({ tune: { ...tune, at, found: false, failed: false }, channelId: channel.id, vod: null, route: "player" });
        this.tuneTimer = setTimeout(() => {
            if (mine === this.tuneTry) launchSafely("tune.wait", () => this.tuneFailed(channel.id));
        }, this.state.prefs.tuneWaitS * 1000);
        const opened = await this.stream.play(channel.url);
        if (mine !== this.tuneTry) return;
        if (!opened) await this.tuneFailed(channel.id);
        else launchSafely("play.guide", () => this.loadGuide(channel));
    }

    /** What the player says, for auto-tune: a picture means this channel works; an error, or none in time, moves on. */
    playback(state: "idle" | "starting" | "playing" | "buffering" | "failed"): void {
        if (state === "playing") this.scrobbleStart();
        const tune = this.state.tune;
        if (!tune || tune.failed) return;
        const id = tune.ids[tune.at];
        if (state === "playing" && !tune.found) {
            this.clearTuneTimer();
            this.set({ tune: { ...tune, found: true } });
            launchSafely("tune.played", () => this.health.played(id));
        } else if (state === "failed" && id === this.state.channelId) {
            launchSafely("tune.failed", () => this.tuneFailed(id));
        }
    }

    private async tuneFailed(id: string): Promise<void> {
        const tune = this.state.tune;
        if (!tune || tune.failed || tune.ids[tune.at] !== id) return;
        this.clearTuneTimer();
        await this.health.failed(id);
        await this.tryTune(tune.at + 1);
    }

    /** Next stream: the channel played, but it's the wrong event; auto-tune carries on from the next. */
    async nextStream(): Promise<void> {
        const tune = this.state.tune;
        if (tune && !tune.failed) await this.tryTune(tune.at + 1);
    }

    /** Try again, after none played: from the best one again. */
    async retryTune(): Promise<void> {
        const tune = this.state.tune;
        if (tune) await this.tryTune(0);
    }

    private endTune(): void {
        this.clearTuneTimer();
        this.tuneTry++;
        if (this.state.tune) this.set({ tune: null });
    }

    private clearTuneTimer(): void {
        if (this.tuneTimer) clearTimeout(this.tuneTimer);
        this.tuneTimer = null;
    }

    hasEventReminder(event: LiveEvent): boolean {
        return !!event.channels[0] && this.reminders.has(event.channels[0], event.start);
    }

    /** The bell on an event that hasn't started: when it starts, Watch auto-tunes it. */
    async toggleEventReminder(event: LiveEvent): Promise<void> {
        const first = event.channels[0];
        if (!first || !event.timeKnown) return;
        const channel = this.state.lineup?.channels.find((c) => c.id === first);
        const on = await this.reminders.toggle({
            channelId: first,
            channelName: channel?.name ?? "",
            title: event.title,
            start: event.start,
            stop: event.stop,
            tune: true,
        });
        this.say({ on, name: event.title, text: on ? t.reminderAdded(event.title) : t.reminderRemoved(event.title) });
        this.scheduleReminders();
    }

    /** The site base the sign-in screen names ("www.tunedplayer.com/tv"). */
    get site(): string {
        return this.services.site ?? ACCOUNT_BASE;
    }

    /** The phone or website signed this stick in to a Tuned account (it holds a seat by now, when one is free). */
    async accountSignedIn(state: SharedAccount): Promise<void> {
        this.set({ account: accountView(state) });
        if (state.signedIn) await this.refreshProfiles();
        // Signed in on the welcome screen: the playlist comes from the account's backup, as on Android.
        if (!this.state.login && state.signedIn) {
            const [first, ...others] = backupLogins(await this.backup());
            if (first) await this.useLogin(first, others);
        }
    }

    /** The website's one-time link: this stick's own token, then Premium. */
    async accountLinked(link: string): Promise<boolean> {
        const state = await this.account.signIn(link);
        if (!state.signedIn) return false;
        await this.accountSignedIn(state);
        return true;
    }

    /** The account's backup (the Android app's sync copy), for its playlists; null when there's none or no answer. */
    private async backup(): Promise<unknown | null> {
        const token = await this.services.store.get(`${PREFIX}account.token`);
        if (!token) return null;
        try {
            const answer = await this.services.http.getJson(`${this.site}/api/backup`, { Authorization: `Bearer ${token}` });
            return answer.status === 200 ? answer.body : null;
        } catch (error) {
            log.warn("AppModel", "backup", error);
            return null;
        }
    }

    /** Settings › Check again: a spinner, then Premium on, none, full, or couldn't reach (keeping what it had). */
    async checkAccount(): Promise<void> {
        const before = this.state.account;
        if (!before || this.state.accountCheck.busy) return;
        this.set({ accountCheck: { busy: true, result: null } });
        const next = accountView(await this.account.refresh());
        if (!next) {
            await this.forgetProfiles();
            this.set({
                account: null,
                accountCheck: { busy: false, result: { ok: false, text: t.accountSignedThisOut } },
            });
        } else if (next.checkedAt === before.checkedAt) {
            this.set({
                account: next,
                accountCheck: { busy: false, result: { ok: false, text: t.premiumUnknown } },
            });
        } else {
            this.set({ account: next, accountCheck: { busy: false, result: { ok: next.premium, text: premiumText(next) } } });
            await this.refreshProfiles();
        }
    }

    async signOutOfAccount(): Promise<void> {
        if (!this.state.account) return;
        await this.account.signOut();
        await this.forgetProfiles();
        this.set({ account: null, accountCheck: { busy: false, result: null } });
    }

    // ---- Profiles (docs/decisions/profiles.md) ----

    /** The profile in use: the one picked, or an account's only profile. */
    currentProfile(): Profile | null {
        const list = this.state.profiles.profiles;
        return list.find((p) => p.id === this.state.profileId) ?? (list.length === 1 ? list[0] : null);
    }

    /** The profiles, the one used last and the teams followed, as the stick last read them (none while signed out). */
    private async readProfiles(): Promise<void> {
        try {
            const [raw, who, follows, lists] = await Promise.all([
                this.shared.get(PROFILES_KEY),
                this.shared.get(PROFILE_KEY),
                this.shared.get(FOLLOWS_KEY),
                this.shared.get(LISTS_KEY),
            ]);
            const keptLists = lists ? (JSON.parse(lists) as AccountLists) : null;
            if (keptLists && keptLists.lists && keptLists.edits) this.set({ lists: keptLists });
            const kept = raw ? (JSON.parse(raw) as AccountProfiles) : null;
            const list = kept && Array.isArray(kept.profiles) ? kept : NO_PROFILES;
            const followed: unknown = follows ? JSON.parse(follows) : [];
            this.set({
                profiles: list,
                profileId: list.profiles.length > 1 && list.profiles.some((p) => p.id === who) ? who : null,
                follows: Array.isArray(followed) ? followed.filter((x): x is string => typeof x === "string") : [],
            });
        } catch (error) {
            log.warn("AppModel", "profiles", error);
        }
    }

    /**
     * Reads the profiles, the parental PIN and the teams followed from the account's backup. Offline, the ones read
     * last stay. A profile removed on the phone stops being used; new profiles ask "Who's watching?" on Live TV.
     */
    async refreshProfiles(): Promise<void> {
        if (!this.state.account) return;
        const text = await this.account.backup().catch(() => null);
        if (!text) return;
        await this.takeBackup(text, true);
    }

    /** The profiles, lists, renamed channels and followed teams in the backup, as read or as the stick just saved it. */
    private async takeBackup(text: string, opening = false): Promise<void> {
        const read = profilesFromBackup(text);
        const follows = followsFromBackup(text);
        const lists = listsFromBackup(text);
        launchSafely("trackers.adopt", () => this.adoptTrackers(text));
        await Promise.all([
            this.shared.set(PROFILES_KEY, JSON.stringify(read)),
            this.shared.set(FOLLOWS_KEY, JSON.stringify(follows)),
            this.shared.set(LISTS_KEY, JSON.stringify(lists)),
        ]).catch((e) => log.warn("AppModel", "save profiles", e));
        const before = this.state.profileId;
        const first = this.state.profiles.profiles[0]?.id;
        const editsChanged = JSON.stringify(lists.edits) !== JSON.stringify(this.state.lists.edits);
        this.set({ profiles: read, follows, lists: editsChanged ? lists : { ...lists, edits: this.state.lists.edits } });
        const still = read.profiles.length > 1 && read.profiles.some((p) => p.id === before) ? before : null;
        if (still !== before || first !== read.profiles[0]?.id) await this.switchProfile(still);
        if (opening && read.profiles.length > 1 && !this.picked && this.state.route === "live") this.go("profiles");
        else this.showFavourites();
    }

    private async forgetProfiles(): Promise<void> {
        await Promise.all([
            this.shared.remove(PROFILES_KEY),
            this.shared.remove(PROFILE_KEY),
            this.shared.remove(FOLLOWS_KEY),
            this.shared.remove(LISTS_KEY),
        ]).catch((e) => log.warn("AppModel", "forget profiles", e));
        const had = this.state.profileId;
        this.set({ profiles: NO_PROFILES, follows: [], lists: NO_LISTS, listFor: null });
        if (had) await this.switchProfile(null);
        else this.showFavourites();
    }

    /** Whether "Who's watching?" has been answered since the app opened. */
    get answered(): boolean {
        return this.picked;
    }

    /** "Who's watching?" from Settings or the top bar. */
    openProfiles(): void {
        this.go("profiles");
    }

    /** Which PIN picking `to` needs (`lockFor`): its own, the parental one leaving a kids profile, or none. */
    lockFor(to: Profile): "none" | "profile" | "parental" {
        const using = this.currentProfile();
        if (using?.id === to.id && this.picked) return "none";
        return lockFor(using, to, this.state.profiles.parentalPin);
    }

    /**
     * Checks a PIN against its hash, five wrong in a row waiting 30 seconds (`PinThrottle`): "ok", "wrong" or "wait".
     */
    async checkPin(pin: string, hash: string | null): Promise<"ok" | "wrong" | "wait"> {
        if (this.pins.waiting()) return "wait";
        const ok = await verifyPin(noblePbkdf2, pin, hash).catch((error: unknown) => {
            log.warn("AppModel", "pin", error);
            return false;
        });
        return this.pins.record(ok);
    }

    /** Watches as `id` from now on, its PIN asked already: its favourites and Continue watching, kids rules, Live TV. */
    async useProfile(id: string): Promise<void> {
        this.picked = true;
        await this.shared.set(PROFILE_KEY, id).catch((e) => log.warn("AppModel", "save profile", e));
        if (id !== this.state.profileId) await this.switchProfile(id);
        await this.seedFavourites();
        this.set({ route: "live" });
    }

    private async switchProfile(id: string | null): Promise<void> {
        this.set({ profileId: id, category: null, movieCategory: null, showCategory: null });
        this.memo.clear();
        this.keepFor(this.state.playlistId);
        await this.loadKept();
    }

    // ---- Account Favourites and your own lists (docs/decisions/lists.md) ----

    /** Account favourites matched on this stick (channels and movies); shows' stay on the stick, as on Samsung. */
    private accountFavs = new Set<string>();
    private index: { lineup: unknown; library: unknown; byHex: Map<string, string> } | null = null;

    /** This playlist's channels, movies and Xtream shows by Android's id (as hex), built once per download. */
    private androidIndex(): Map<string, string> {
        const { lineup, library } = this.state;
        if (this.index && this.index.lineup === lineup && this.index.library === library) return this.index.byHex;
        const byHex = new Map<string, string>();
        for (const c of lineup?.channels ?? []) byHex.set(androidIdHex("live", c.url, c.name), c.id);
        for (const m of library?.movies ?? []) if (m.url) byHex.set(androidIdHex("movie", m.url, m.name), m.id);
        this.index = { lineup, library, byHex };
        return byHex;
    }

    /** Android's id for a channel or a movie on this stick; null for a show, which Android doesn't favourite or list. */
    androidIdOf(id: string): string | null {
        const channel = this.state.lineup?.channels.find((c) => c.id === id);
        if (channel) return hexToId(androidIdHex("live", channel.url, channel.name));
        const movie = this.state.library?.movies.find((m) => m.id === id);
        return movie?.url ? hexToId(androidIdHex("movie", movie.url, movie.name)) : null;
    }

    /** The stick's ids for Android ids, in their order, leaving out what this playlist doesn't have. */
    private onStick(androidIds: readonly string[]): string[] {
        const index = this.androidIndex();
        return androidIds.flatMap((a) => {
            const hex = idToHex(a);
            const id = hex ? index.get(hex) : undefined;
            return id ? [id] : [];
        });
    }

    /**
     * Favourites as shown: signed in to an account with profiles, the account's for channels and movies (as the phone
     * and website keep them) and the stick's own for shows; otherwise the stick's own, per playlist and profile.
     */
    private showFavourites(fromAccount = true): void {
        const profile = this.state.account ? this.currentProfile() : null;
        const local = this.favourites?.all ?? new Set<string>();
        if (!profile) return this.set({ favourites: local });
        if (fromAccount) this.accountFavs = new Set(this.onStick(profile.favourites));
        const shows = new Set((this.state.library?.shows ?? []).map((x) => x.id));
        this.set({ favourites: new Set([...this.accountFavs, ...[...local].filter((x) => shows.has(x))]) });
    }

    /** Kept for the callers that matched favourites before they lived in the account. */
    private async seedFavourites(): Promise<void> {
        this.showFavourites();
    }

    /** Your own lists for the profile in use; Premium, as on Android, so none on a free or signed-out stick. */
    myLists(): readonly UserList[] {
        const profile = this.state.account ? this.currentProfile() : null;
        return profile && hasPremium(this.state.account) ? (this.state.lists.lists[profile.id] ?? []) : [];
    }

    /** Whether "Add to list" can save: a profile in the account, and Premium. */
    canUseLists(): boolean {
        return !!this.state.account && !!this.currentProfile() && hasPremium(this.state.account);
    }

    /** What of `items` is in `list`: channels in the order they were added, movies in the playlist's order. */
    inList<T extends { id: string }>(list: UserList, items: readonly T[]): T[] {
        const ids = this.onStick(list.items);
        const byId = new Map(items.map((x) => [x.id, x]));
        const found = ids.flatMap((id) => byId.get(id) ?? []);
        if (found.length && "url" in found[0] && "number" in found[0]) return found;
        const set = new Set(ids);
        return items.filter((x) => set.has(x.id));
    }

    /** Each list holding something of a kind, with how many, after Favourites in Live TV and Movies. */
    listCategories(kind: "live" | "movies"): { id: string; name: string; count: number }[] {
        const items: readonly { id: string }[] = kind === "live" ? this.shownChannels() : this.shownTitles("movies");
        return this.myLists()
            .map((l) => ({ id: LIST + l.id, name: l.name, count: this.inList(l, items).length }))
            .filter((c) => c.count > 0);
    }

    /** Hold OK on a channel, or a movie page's button: "Add to list" with Favourites first. */
    openAddToList(id: string, name: string): void {
        this.set({ listFor: { id, name }, listSave: { busy: null, problem: null } });
    }

    closeAddToList(): void {
        this.set({ listFor: null });
    }

    private changes: Promise<unknown> = Promise.resolve();

    /** Makes `change` in the account's backup, after any change still saving. Null when it stuck. */
    private changeAccount(busy: string, change: ListChange): Promise<ListProblem | null> {
        const run = this.changes.then(async (): Promise<ListProblem | null> => {
            this.set({ listSave: { busy, problem: null } });
            let problem: ListProblem | null = null;
            try {
                const done = await changeBackup(this.account, change, { random: (b) => (b.set(this.services.random(b.length)), b) });
                if ("problem" in done) problem = done.problem;
                else await this.takeBackup(done.text);
            } catch (error) {
                log.warn("AppModel", "change account", error);
                problem = "offline";
            }
            if (problem) log.info("Lists", `A change to the account didn't stick: ${problem}`);
            this.set({ listSave: { busy: null, problem } });
            return problem;
        });
        this.changes = run.catch(() => null);
        return run;
    }

    /** Ticks or unticks a list for the item "Add to list" is open for. */
    async setInList(listId: string, on: boolean): Promise<void> {
        const profile = this.currentProfile();
        const item = this.state.listFor && this.androidIdOf(this.state.listFor.id);
        if (!profile || !item) return;
        await this.changeAccount(listId, { op: "list_item", profileId: profile.id, listId, itemId: item, on });
    }

    /** A new list with the item "Add to list" is open for already inside. True when it stuck. */
    async newList(name: string): Promise<boolean> {
        const profile = this.currentProfile();
        const item = this.state.listFor ? this.androidIdOf(this.state.listFor.id) : null;
        if (!profile) return false;
        const change: ListChange = { op: "list_add", profileId: profile.id, name, ...(item ? { itemId: item } : {}) };
        return !(await this.changeAccount("new", change));
    }

    /** Settings › Your lists › Delete (pressed twice). Renaming stays on the phone or website. */
    async deleteList(listId: string): Promise<boolean> {
        const profile = this.currentProfile();
        if (!profile) return false;
        return !(await this.changeAccount(listId, { op: "list_delete", profileId: profile.id, listId }));
    }

    /** Whether this build has the service's id; without it, nothing of it shows. */
    trackerAvailable(name: Tracker): boolean {
        return this.trackers[name].available;
    }

    /** Trakt and Simkl are Premium, as on Android ([trakt](trakt.md), [simkl](simkl.md)). */
    trackerOn(name: Tracker): boolean {
        return this.trackers[name].available && hasPremium(this.state.account);
    }

    private setTracker(name: Tracker, patch: Partial<TrackerView>): void {
        const trackers = this.state.trackers;
        this.set({ trackers: { ...trackers, [name]: { ...trackers[name], ...patch } } });
    }

    private async readTrackers(): Promise<void> {
        await Promise.all(TRACKERS.map((name) => this.readTracker(name)));
    }

    private async readTracker(name: Tracker): Promise<void> {
        try {
            const tracker = this.trackers[name];
            const [now, scrobble] = await Promise.all([tracker.state(), tracker.scrobbleOn()]);
            this.setTracker(name, { signedIn: now.signedIn, user: now.user, scrobble });
        } catch (e) {
            log.warn("AppModel", `${name} state`, e);
        }
    }

    /** The phone's sign-ins and Scrobble switches from the account's backup, taken when newer (one sign-in covers every device). */
    private async adoptTrackers(text: string): Promise<void> {
        const found = {
            trakt: { session: traktFromBackup(text), scrobble: traktScrobbleFromBackup(text) },
            simkl: { session: simklFromBackup(text), scrobble: simklScrobbleFromBackup(text) },
        };
        for (const name of TRACKERS) {
            const tracker = this.trackers[name];
            const { session, scrobble } = found[name];
            const took = await (tracker as Simkl).adopt(session);
            if (took && scrobble !== null) await tracker.setScrobble(scrobble);
            if (took) await this.readTracker(name);
        }
    }

    /**
     * Settings › Trakt or Simkl › Connect: shows a code to type at trakt.tv/activate or simkl.com/pin and asks each few
     * seconds until it's typed, runs out, or the person goes back. The tokens stay on the stick and are never logged.
     */
    async connectTracker(name: Tracker): Promise<void> {
        if (this.state.trackers[name].busy) return;
        const tracker = this.trackers[name];
        const run = ++this.trackerRun[name];
        const stale = () => run !== this.trackerRun[name];
        this.setTracker(name, { busy: true, code: null, problem: null });
        const code = await tracker.startSignIn();
        if (stale()) return;
        if (!code) return this.setTracker(name, { busy: false, problem: "offline" });
        this.setTracker(name, { code });
        let wait = code.intervalMs;
        for (;;) {
            await new Promise((resolve) => setTimeout(resolve, wait));
            if (stale()) return;
            const answer = await tracker.pollSignIn(code);
            if (stale()) return;
            if (answer === "waiting") continue;
            if (answer === "slow down") {
                wait = name === "trakt" ? Trakt.slowDown(wait) : Simkl.slowDown(wait);
                continue;
            }
            if (typeof answer === "object") {
                return this.setTracker(name, { busy: false, code: null, signedIn: true, user: answer.user, problem: null });
            }
            return this.setTracker(name, { busy: false, code: null, problem: answer });
        }
    }

    /** Back, or the Back button, while the code shows. */
    cancelTracker(name: Tracker): void {
        this.trackerRun[name]++;
        this.setTracker(name, { busy: false, code: null });
    }

    /** Settings › Disconnect: forgets the sign-in and tells the service to drop the token. */
    async disconnectTracker(name: Tracker): Promise<void> {
        this.setTracker(name, { busy: true, problem: null });
        await this.trackers[name].signOut().catch((e) => log.warn("AppModel", `${name} sign out`, e));
        this.setTracker(name, { busy: false, signedIn: false, user: null });
    }

    async setTrackerScrobble(name: Tracker, on: boolean): Promise<void> {
        this.setTracker(name, { scrobble: on });
        await this.trackers[name].setScrobble(on).catch((e) => log.warn("AppModel", `${name} scrobble`, e));
    }

    /** The player paused or went on (the remote's play and pause), told to Trakt and Simkl as Android does. */
    pauseChanged(paused: boolean): void {
        if (!this.scrobbling) return;
        this.scrobbleSend(paused ? "pause" : "start", this.scrobbling);
    }

    /** A movie or episode started playing: tells each service once per play, from where it is. */
    private scrobbleStart(): void {
        const media = this.state.vod?.scrobble;
        if (!media || this.state.vod?.replay || this.scrobbling === media) return;
        this.scrobbling = media;
        this.scrobbleSend("start", media);
    }

    /** It ended, closed or something else was picked: a stop with how far it got (watched from 80%). */
    private scrobbleEnd(): void {
        const media = this.scrobbling;
        this.scrobbling = null;
        if (media) this.scrobbleSend("stop", media);
    }

    private scrobbleSend(kind: "start" | "pause" | "stop", media: ScrobbleMedia): void {
        const where = this.services.player.position?.() ?? { at: 0, length: 0 };
        const progress = simklProgress(where.at * 1000, where.length * 1000);
        for (const name of TRACKERS) {
            const view = this.state.trackers[name];
            if (!this.trackerOn(name) || !view.signedIn || !view.scrobble) continue;
            launchSafely(`${name}.${kind}`, async () => {
                const answer = await this.trackers[name].scrobble(kind, media, progress);
                if (answer === "signed out") await this.readTracker(name);
            });
        }
    }

    /** Sign out: back to how the stick was installed (AGENTS.md), the account signed out too. */
    async signOut(): Promise<void> {
        this.scrobbleEnd();
        await this.stream.stop();
        for (const name of TRACKERS) await this.trackers[name].forget().catch((e) => log.warn("AppModel", `${name} forget`, e));
        if (this.state.account) await this.account.signOut();
        await this.saved.reset();
        clearSecrets();
        this.set({
            route: "signin",
            login: null,
            lineup: null,
            account: null,
            guide: {},
            channelId: null,
            category: null,
            refresh: { busy: false },
            accountCheck: { busy: false },
            library: null,
            libraryLoad: { busy: false },
            movieCategory: null,
            showCategory: null,
            title: null,
            episodes: {},
            episodesLoad: { id: null, busy: false, failed: false },
            vod: null,
            trackers: NO_TRACKERS,
            positions: {},
            watching: [],
            prefs: DEFAULT_PREFS,
            favourites: new Set(),
            details: {},
            detailsLoad: { id: null, busy: false, failed: false },
            seasons: {},
            query: "",
            adding: false,
            grid: {},
            gridLoad: { busy: false },
            reminders: [],
            due: null,
            events: [],
            eventsLoad: { busy: false },
            follows: [],
            tune: null,
            profiles: NO_PROFILES,
            profileId: null,
            lists: NO_LISTS,
            listFor: null,
            listSave: { busy: null, problem: null },
        });
        this.picked = false;
        this.endTune();
        this.gridAsked.clear();
        this.linkGuideRead = false;
        if (this.reminderTimer) clearTimeout(this.reminderTimer);
        this.generation++;
        this.saved_playlists = [];
        this.keepFor(FIRST);
        this.showPlaylists(FIRST);
        await Promise.all([this.loadKept(), this.loadPrefs()]);
        this.libraryRead = false;
    }
}

/** Premium only from the Tuned account; "full" alone locks, and an outage keeps what the stick last heard. */
export function premiumText(account: AccountState | null): string {
    if (!account) return t.accountSignedOut;
    switch (account.seat) {
        case "active":
            return t.premiumOn;
        case "full":
            return t.premiumFull;
        case "none":
            return t.premiumNone;
        default:
            return account.premium ? t.premiumOn : t.premiumChecking;
    }
}

export const hasPremium = (account: AccountState | null) => !!account && account.premium && account.seat !== "full";

function rememberSecrets(login: Login | null): void {
    if (!login) return;
    if (login.kind === "xtream") {
        addSecret(login.username);
        addSecret(login.password);
    } else {
        addSecret(login.playlistUrl);
    }
}
