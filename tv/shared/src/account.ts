// The Tuned account on a TV (docs/decisions/tizen.md): the TV signs in by showing a code that a phone or the website
// answers (the relay in devices/lib/tv.mjs), never by typing a Google password with a remote. Premium on a TV comes
// only from the account (Melvin, 2026-11-26, as on Fire TV): there is no purchase on the TV.
import { b64, hex, unb64 } from "./bytes.ts";
import { checkDigits, displayCode, newKeys, openBox, unpack, type TvKeys } from "./handoff.ts";
import { fromHandoff, handoffAccountLink, type PlaylistLogin } from "./login.ts";
import type { CryptoLike, Fetch, Gunzip, KeyValueStore, Log } from "./platform.ts";

export const ACCOUNT_BASE = "https://www.tunedplayer.com";

/** How a TV build names itself to the account, for the device list and the admin's counts. */
export interface DeviceInfo {
    readonly kind: "tv";
    readonly build: "tizen" | "vega";
    /** "Samsung TV", "Fire TV": what the account's device list shows. */
    readonly name: string;
}

export interface AccountDeps {
    readonly fetch: Fetch;
    readonly store: KeyValueStore;
    readonly crypto: CryptoLike;
    readonly gunzip: Gunzip;
    readonly device: DeviceInfo;
    readonly base?: string;
    readonly log?: Log;
}

/** What the TV knows about its account. `premium` is the last answer from the server, kept for when it's offline. */
export interface AccountState {
    readonly signedIn: boolean;
    readonly email: string;
    readonly premium: boolean;
    /** When the server last answered, epoch milliseconds; 0 for never. */
    readonly checkedAt: number;
}

export const SIGNED_OUT: AccountState = { signedIn: false, email: "", premium: false, checkedAt: 0 };

const KEYS = { device: "account.device", token: "account.token", state: "account.state" };

/** One code on the screen: what to show, and the secret only this TV polls with. */
export interface SignInCode {
    readonly code: string;
    /** "K7QF-29MD". */
    readonly display: string;
    /** The QR code's link: tunedplayer.com/tv#CODE.KEY. The page there can send a playlist or sign the TV in. */
    readonly link: string;
    /** Shown beside the code; the phone shows the same four digits when the code was typed. */
    readonly digits: string;
    readonly expiresAt: number;
    readonly secret: string;
    readonly keys: TvKeys;
}

/** What one poll of the relay found. */
export type Taken =
    | { readonly kind: "waiting" }
    /** The code ran out or was used: make a new one. */
    | { readonly kind: "gone" }
    | { readonly kind: "unreachable" }
    /** The phone sent playlists (and maybe signed the TV in to the account too). */
    | { readonly kind: "playlists"; readonly playlists: PlaylistLogin[]; readonly signedIn: boolean }
    /** The website or phone signed the TV in to the account. */
    | { readonly kind: "signed in"; readonly state: AccountState };

export class TunedAccount {
    private readonly deps: AccountDeps;
    private readonly base: string;

    constructor(deps: AccountDeps) {
        this.deps = deps;
        this.base = (deps.base ?? ACCOUNT_BASE).replace(/\/+$/, "");
    }

    private log(message: string): void {
        this.deps.log?.("Account", message);
    }

    /** This TV's id for the account's device list: 32 random hex digits, made once. */
    async deviceId(): Promise<string> {
        const kept = await this.deps.store.get(KEYS.device);
        if (kept && /^[0-9a-f]{32,64}$/.test(kept)) return kept;
        const id = hex(this.deps.crypto.getRandomValues(new Uint8Array(16)));
        await this.deps.store.set(KEYS.device, id);
        return id;
    }

    async state(): Promise<AccountState> {
        try {
            const raw = await this.deps.store.get(KEYS.state);
            return raw ? { ...SIGNED_OUT, ...(JSON.parse(raw) as Partial<AccountState>) } : SIGNED_OUT;
        } catch {
            return SIGNED_OUT;
        }
    }

    private async post(path: string, body: unknown, token?: string): Promise<{ status: number; json: Record<string, unknown> } | null> {
        try {
            const headers: Record<string, string> = { "Content-Type": "application/json" };
            if (token) headers.Authorization = `Bearer ${token}`;
            const response = await this.deps.fetch(`${this.base}/${path}`, { method: "POST", headers, body: JSON.stringify(body) });
            const json = (await response.json().catch(() => ({}))) as Record<string, unknown>;
            return { status: response.status, json: json ?? {} };
        } catch (error) {
            this.log(`Couldn't reach ${path}: ${String((error as Error)?.message ?? error)}`);
            return null;
        }
    }

    /** A new code for the screen, or null when the relay can't be reached (the screen says so, with Try again). */
    async newCode(): Promise<SignInCode | null> {
        const keys = await newKeys(this.deps.crypto);
        const reply = await this.post("api/tv/code", { key: b64(keys.publicKey) });
        const code = reply?.json.code;
        const secret = reply?.json.secret;
        if (reply?.json.status !== "ok" || typeof code !== "string" || typeof secret !== "string") return null;
        const seconds = typeof reply.json.seconds === "number" ? reply.json.seconds : 600;
        return {
            code,
            display: displayCode(code),
            link: `${this.base}/tv#${code}.${b64(keys.publicKey)}`,
            digits: await checkDigits(keys.publicKey, this.deps.crypto),
            expiresAt: Date.now() + seconds * 1000,
            secret,
            keys,
        };
    }

    /** Asks the relay once whether the phone or website has answered [code]. Call every few seconds while it shows. */
    async take(code: SignInCode): Promise<Taken> {
        if (Date.now() > code.expiresAt) return { kind: "gone" };
        const reply = await this.post("api/tv/take", { code: code.code, secret: code.secret });
        if (!reply) return { kind: "unreachable" };
        const { status, box, link } = reply.json;
        if (status === "waiting") return { kind: "waiting" };
        if (status === "not found") return { kind: "gone" };
        if (status !== "ok") return { kind: "unreachable" };
        let signedIn = false;
        if (typeof link === "string") signedIn = (await this.signIn(link)).signedIn;
        if (typeof box === "string") {
            try {
                const payload = await openBox(unb64(box), code.code, code.keys, this.deps.crypto);
                const json = await unpack(payload, this.deps.gunzip);
                const sealedLink = handoffAccountLink(json);
                if (sealedLink && !signedIn) signedIn = (await this.signIn(sealedLink)).signedIn;
                return { kind: "playlists", playlists: fromHandoff(json), signedIn };
            } catch (error) {
                this.log(`A box from the phone didn't open: ${(error as Error)?.name ?? "error"}`);
                return { kind: "gone" };
            }
        }
        return signedIn ? { kind: "signed in", state: await this.state() } : { kind: "gone" };
    }

    /** Swaps a one-time link code for this TV's own token, then reads the account. */
    async signIn(link: string): Promise<AccountState> {
        const reply = await this.post("api/auth/token", { link, device: await this.deviceId(), ...this.deps.device });
        const token = reply?.json.token;
        if (reply?.json.status !== "ok" || typeof token !== "string") return this.state();
        await this.deps.store.set(KEYS.token, token);
        await this.save({ signedIn: true, email: String(reply.json.email ?? ""), premium: false, checkedAt: 0 });
        return this.refresh();
    }

    /**
     * Asks the account for its Premium status. Offline, the last answer stands, so a TV that can't reach the server
     * keeps what it had; only a clear "signed out" (the device was removed on the website) signs it out.
     */
    async refresh(): Promise<AccountState> {
        const token = await this.deps.store.get(KEYS.token);
        const kept = await this.state();
        if (!token) return kept.signedIn ? this.signOutLocally() : kept;
        try {
            const response = await this.deps.fetch(`${this.base}/api/account/me`, { headers: { Authorization: `Bearer ${token}` } });
            if (response.status === 401) return this.signOutLocally();
            if (!response.ok) return kept;
            const me = (await response.json()) as { email?: unknown; premium?: unknown };
            return this.save({
                signedIn: true,
                email: String(me.email ?? kept.email),
                premium: me.premium === true,
                checkedAt: Date.now(),
            });
        } catch (error) {
            this.log(`Couldn't check the account: ${String((error as Error)?.message ?? error)}`);
            return kept;
        }
    }

    /** Signs this TV out of the account, on the server too when it can be reached. */
    async signOut(): Promise<AccountState> {
        const token = await this.deps.store.get(KEYS.token);
        if (token) await this.post("api/auth/signout", {}, token);
        return this.signOutLocally();
    }

    private async signOutLocally(): Promise<AccountState> {
        await this.deps.store.remove(KEYS.token);
        await this.deps.store.remove(KEYS.state);
        return SIGNED_OUT;
    }

    private async save(state: AccountState): Promise<AccountState> {
        await this.deps.store.set(KEYS.state, JSON.stringify(state));
        return state;
    }
}
