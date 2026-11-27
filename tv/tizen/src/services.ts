// Trakt and Simkl on the TV (docs/decisions/trakt.md, simkl.md): the two accounts, signed in with a code typed on a
// phone, the Scrobble switch, and what the player tells them. Both come from tv/shared; this file only hands them the
// TV's network and store, seals their tokens like the logins (security.md), and reads them from the account's backup,
// so connecting on the phone covers the TV.
import type { Item } from "@tuned/shared";
import type { KeyValueStore } from "@tuned/shared/platform";
import { Simkl, simklFromBackup } from "@tuned/shared/simkl";
import { Trakt, traktFromBackup, traktProgress, traktScrobbleFromBackup, type ScrobbleMedia } from "@tuned/shared/trakt";
import { kv } from "./db.ts";
import { log } from "./log.ts";
import { tvFetch } from "./net.ts";
import { seal, unseal } from "./secret.ts";
import { store } from "./state.ts";
import { onTv } from "./tizen.ts";

/** The tokens are sealed like the logins; the Scrobble switch isn't a secret. */
const sealedStore: KeyValueStore = {
    get: async (key) => (key.endsWith(".token") ? unseal(await kv.get(key)) : kv.get(key)),
    set: async (key, value) => kv.set(key, key.endsWith(".token") ? await seal(value) : value),
    remove: (key) => kv.remove(key),
};

const built = typeof __KEYS__ === "object" ? __KEYS__ : { trakt: "", simkl: "" };
// A build without an id hides that service on a TV. A desktop browser (development and the e2e test, which stands in
// for both services) gets a placeholder id so the screens can be tried.
const idFor = (id: string, name: string) => id || (onTv() ? "" : `dev-${name}`);

export const trakt = new Trakt({ fetch: tvFetch, store: sealedStore, clientId: idFor(built.trakt, "trakt"), log });
export const simkl = new Simkl({
    fetch: tvFetch,
    store: sealedStore,
    clientId: idFor(built.simkl, "simkl"),
    appVersion: typeof __VERSION__ === "string" ? __VERSION__ : "tv",
    log,
});

export interface Service {
    readonly name: "Trakt" | "Simkl";
    readonly client: Trakt | Simkl;
    /** How much longer to wait between asks after the service says to slow down. */
    readonly slowDown: (ms: number) => number;
}

export const services: readonly Service[] = [
    { name: "Trakt" as const, client: trakt, slowDown: Trakt.slowDown },
    { name: "Simkl" as const, client: simkl, slowDown: Simkl.slowDown },
].filter((s) => s.client.available);

export interface ServiceState {
    readonly signedIn: boolean;
    readonly user: string | null;
    readonly scrobble: boolean;
}

/** Each service's sign-in and switch, for Settings. */
export const serviceStates = store<Readonly<Record<string, ServiceState>>>({});

export async function readServices(): Promise<void> {
    const next: Record<string, ServiceState> = {};
    for (const s of services) {
        try {
            const state = await s.client.state();
            next[s.name] = { ...state, scrobble: await s.client.scrobbleOn() };
        } catch (error) {
            log(s.name, `Couldn't read the sign-in: ${String((error as Error)?.message ?? error)}`);
            next[s.name] = { signedIn: false, user: null, scrobble: true };
        }
    }
    serviceStates.set(next);
}

/** The sign-ins another device keeps in the account's backup, when newer than the TV's own. */
export async function takeServicesFromBackup(text: string): Promise<void> {
    try {
        const tookTrakt = await trakt.adopt(traktFromBackup(text));
        await simkl.adopt(simklFromBackup(text));
        const scrobble = traktScrobbleFromBackup(text);
        if (tookTrakt && scrobble !== null) await trakt.setScrobble(scrobble);
        await readServices();
    } catch (error) {
        log("Services", `Couldn't take the sign-ins from the account: ${String((error as Error)?.message ?? error)}`);
    }
}

/** Signing out of Tuned: forgets both here, leaving the phone and the other devices signed in. */
export async function forgetServices(): Promise<void> {
    await Promise.all(services.map((s) => s.client.forget().catch(() => undefined)));
    await readServices();
}

/** The movie or episode behind an item, or null for what isn't sent: live TV, a recording, an episode without numbers. */
export function mediaOf(item: Item): ScrobbleMedia | null {
    if (item.id.startsWith("r")) return null;
    if (item.kind === "movie") return { type: "movie", title: item.name, year: item.year };
    if (item.kind === "series" && item.showName && item.season > 0 && item.episode > 0)
        return { type: "episode", show: item.showName, year: item.year, season: item.season, episode: item.episode };
    return null;
}

let queue: Promise<unknown> = Promise.resolve();

/**
 * Tells every signed-in service what the player did, in order (a stop never overtakes its start). Never throws; a
 * service that has signed the TV out is shown as signed out in Settings.
 */
export function scrobble(event: "start" | "pause" | "stop", item: Item, positionMs: number, durationMs: number): Promise<void> {
    const media = mediaOf(item);
    if (!media || !services.length) return Promise.resolve();
    const progress = traktProgress(positionMs, durationMs);
    const next = queue.then(async () => {
        const answers = await Promise.all(services.map((s) => s.client.scrobble(event, media, progress).catch(() => "offline" as const)));
        if (answers.includes("signed out") && services.some((s, i) => answers[i] === "signed out" && serviceStates.get()[s.name]?.signedIn))
            await readServices();
    });
    queue = next.catch(() => undefined);
    return next.catch(() => undefined);
}
