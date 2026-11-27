// The TV sign-in relay and the Tuned account, over the same endpoints the Android TV uses (devices/lib/tv.mjs,
// devices/api/auth/token.mjs, devices/api/account/seat.mjs). Premium on Vega comes only with the Tuned account, as on
// the Fire OS build (Melvin, 2026-11-27: "Tuned account only"). Replaced by @tuned/shared's account client when it lands.
import type { Http } from "./http";
import type { AccountState } from "./storage";

export const SITE = "https://www.tunedplayer.com";

export interface RelayCode {
    code: string;
    secret: string;
    seconds: number;
}

export type Taken =
    { kind: "waiting" } | { kind: "gone" } | { kind: "unreachable" } | { kind: "box"; box: string } | { kind: "link"; link: string };

export interface DeviceInfo {
    device: string;
    name: string;
}

export function accountClient(http: Http, base = SITE) {
    const api = (path: string) => `${base}${path}`;
    const auth = (token: string) => ({ Authorization: `Bearer ${token}` });

    return {
        base,

        /** A code for this stick's public key, or null when the relay can't be reached. */
        async code(publicKey: string): Promise<RelayCode | null> {
            try {
                const answer = await http.postJson(api("/api/tv/code"), { key: publicKey });
                const b = answer.body;
                return b?.status === "ok" && typeof b.code === "string"
                    ? { code: b.code, secret: b.secret, seconds: Number(b.seconds) || 600 }
                    : null;
            } catch {
                return null;
            }
        },

        async take(code: RelayCode): Promise<Taken> {
            try {
                const answer = await http.postJson(api("/api/tv/take"), { code: code.code, secret: code.secret });
                const b = answer.body;
                if (answer.status === 404) return { kind: "gone" };
                if (b?.status === "waiting") return { kind: "waiting" };
                if (b?.status === "ok" && typeof b.link === "string") return { kind: "link", link: b.link };
                if (b?.status === "ok" && typeof b.box === "string") return { kind: "box", box: b.box };
                return { kind: "unreachable" };
            } catch {
                return { kind: "unreachable" };
            }
        },

        /** Swaps the website's one-time link for this stick's own token. */
        async signIn(link: string, device: DeviceInfo): Promise<{ token: string; email: string } | null> {
            try {
                const answer = await http.postJson(api("/api/auth/token"), {
                    link,
                    device: device.device,
                    kind: "tv",
                    build: "vega",
                    name: device.name,
                });
                const b = answer.body;
                return b?.status === "ok" && typeof b.token === "string" ? { token: b.token, email: String(b.email ?? "") } : null;
            } catch {
                return null;
            }
        },

        /**
         * Asks for a Premium seat. Null when the server can't be reached, so the caller keeps what it last heard
         * (a paying person is never locked out by an outage); "signed out" when the token was revoked.
         */
        async seat(token: string): Promise<Pick<AccountState, "premium" | "seat"> | "signed out" | null> {
            try {
                const answer = await http.postJson(api("/api/account/seat"), { use: true, purchases: [] }, auth(token));
                if (answer.status === 401) return "signed out";
                const b = answer.body;
                if (!b || !["active", "full", "none"].includes(b.seat)) return null;
                return { premium: b.seat === "active", seat: b.seat };
            } catch {
                return null;
            }
        },

        /** The account's backup (the Android app's sync copy), for its playlists; null when there's none or no answer. */
        async backup(token: string): Promise<unknown | null> {
            try {
                const answer = await http.getJson(api("/api/backup"), auth(token));
                return answer.status === 200 ? answer.body : null;
            } catch {
                return null;
            }
        },

        async signOut(token: string): Promise<void> {
            try {
                await http.postJson(api("/api/auth/signout"), {}, auth(token));
            } catch {
                // Signing out on the stick doesn't wait for the server; the token is forgotten either way.
            }
        },
    };
}

export type AccountClient = ReturnType<typeof accountClient>;
