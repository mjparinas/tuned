// The app's state and what changes it, apart from the screens, so it's tested on Node. Opening shows the channels
// saved on the stick at once and refreshes after the first screen is up (AGENTS.md: nothing waits on the network);
// every refresh, sign-in and account check reports progress and how it ended.
import { ACCOUNT_BASE, TunedAccount, type AccountState as SharedAccount } from "@tuned/shared/account";
import type { Fetch, KeyValueStore } from "@tuned/shared/platform";
import type { Http } from "../core/http";
import { addSecret, clearSecrets, launchSafely, log } from "../core/log";
import type { Channel, Lineup, Login, Programme } from "../core/models";
import { OneStream, type StreamPlayer } from "../core/oneStream";
import { downloadLineup, failureText, isStale } from "../core/playlist";
import type { StepProgress } from "../core/progress";
import { PREFIX, storage, type Storage, type Store } from "../core/storage";
import { gunzip, nobleCrypto } from "../platform/crypto";
import { backupLogin } from "./logins";
import { parseShortEpg, shortEpgUrl } from "../core/xtream";

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

export type Route = "boot" | "signin" | "manual" | "loading" | "live" | "player" | "settings" | "account";

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
}

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
    };
    readonly saved: Storage;
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
        if (!login) return this.set({ route: "signin", account });
        if (!lineup) {
            this.set({ login, account, route: "loading" });
            return this.firstDownload();
        }
        this.set({ login, lineup, account, route: "live", channelId: last ?? lineup.channels[0]?.id ?? null });
        launchSafely("boot.background", async () => {
            await this.services.quietStart?.();
            if (isStale(lineup)) await this.refreshNow();
            if (account) await this.checkAccount();
        });
    }

    /** A login from the phone or the form: saved, then the first download. */
    async useLogin(login: Login): Promise<void> {
        rememberSecrets(login);
        await this.saved.saveLogin(login);
        this.set({ login, lineup: null, route: "loading" });
        await this.firstDownload();
    }

    async firstDownload(): Promise<void> {
        const login = this.state.login;
        if (!login) return this.go("signin");
        this.set({ loading: { progress: null, error: null } });
        try {
            const lineup = await downloadLineup(this.services.http, login, (progress) => this.set({ loading: { progress, error: null } }));
            await this.saved.saveLineup(lineup);
            this.set({
                lineup,
                route: "live",
                category: null,
                channelId: lineup.channels[0]?.id ?? null,
                loading: { progress: null, error: null },
            });
        } catch (error) {
            log.warn("AppModel", "first download", error);
            this.set({ loading: { progress: null, error: failureText(error) } });
        }
    }

    /** Settings › Refresh channels now, and the background refresh: progress while it runs, then how it ended. */
    async refreshNow(): Promise<void> {
        const login = this.state.login;
        if (!login || this.state.refresh.busy) return;
        this.set({ refresh: { busy: true, progress: null, result: null } });
        try {
            const lineup = await downloadLineup(this.services.http, login, (progress) => this.set({ refresh: { busy: true, progress } }));
            await this.saved.saveLineup(lineup);
            this.set({ lineup, refresh: { busy: false, result: { ok: true, text: "Channels are up to date." } } });
        } catch (error) {
            log.warn("AppModel", "refresh", error);
            this.set({ refresh: { busy: false, result: { ok: false, text: failureText(error) } } });
        }
    }

    pickCategory(id: string | null): void {
        this.set({ category: id });
    }

    visibleChannels(): Channel[] {
        const { lineup, category } = this.state;
        if (!lineup) return [];
        return category ? lineup.channels.filter((c) => c.categoryId === category) : lineup.channels;
    }

    /** Plays a channel: the old stream closes before this one opens. */
    async play(channel: Channel): Promise<void> {
        this.set({ channelId: channel.id, route: "player" });
        launchSafely("play.remember", () => this.saved.saveLastChannel(channel.id));
        await this.stream.play(channel.url);
        launchSafely("play.guide", () => this.loadGuide(channel));
    }

    /** Up and down in the player: the next or previous channel in the list being shown, wrapping round. */
    async zap(step: 1 | -1): Promise<void> {
        const list = this.visibleChannels();
        if (!list.length) return;
        const at = Math.max(
            0,
            list.findIndex((c) => c.id === this.state.channelId),
        );
        await this.play(list[(at + step + list.length) % list.length]);
    }

    async stopPlaying(): Promise<void> {
        await this.stream.stop();
        this.set({ route: "live" });
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

    /** The site base the sign-in screen names ("www.tunedplayer.com/tv"). */
    get site(): string {
        return this.services.site ?? ACCOUNT_BASE;
    }

    /** The phone or website signed this stick in to a Tuned account (it holds a seat by now, when one is free). */
    async accountSignedIn(state: SharedAccount): Promise<void> {
        this.set({ account: accountView(state) });
        // Signed in on the welcome screen: the playlist comes from the account's backup, as on Android.
        if (!this.state.login && state.signedIn) {
            const restored = backupLogin(await this.backup());
            if (restored) await this.useLogin(restored);
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
            this.set({
                account: null,
                accountCheck: { busy: false, result: { ok: false, text: "Your Tuned account signed this TV out." } },
            });
        } else if (next.checkedAt === before.checkedAt) {
            this.set({
                account: next,
                accountCheck: { busy: false, result: { ok: false, text: "Couldn't reach your Tuned account. Premium stays as it was." } },
            });
        } else {
            this.set({ account: next, accountCheck: { busy: false, result: { ok: next.premium, text: premiumText(next) } } });
        }
    }

    async signOutOfAccount(): Promise<void> {
        if (!this.state.account) return;
        await this.account.signOut();
        this.set({ account: null, accountCheck: { busy: false, result: null } });
    }

    /** Sign out: back to how the stick was installed (AGENTS.md), the account signed out too. */
    async signOut(): Promise<void> {
        await this.stream.stop();
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
        });
    }
}

/** Premium only from the Tuned account; "full" alone locks, and an outage keeps what the stick last heard. */
export function premiumText(account: AccountState | null): string {
    if (!account) return "Sign in to your Tuned account to use Premium on this TV.";
    switch (account.seat) {
        case "active":
            return "Premium is on for this TV.";
        case "full":
            return "Premium is on every device this account allows. Free a seat on another device, then check again.";
        case "none":
            return "This account doesn't have Premium yet.";
        default:
            return account.premium ? "Premium is on for this TV." : "Checking Premium…";
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
