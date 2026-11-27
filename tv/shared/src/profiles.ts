// "Who's watching?" on the TV apps (docs/decisions/profiles.md): the profiles the Tuned account's backup holds, as
// the phone and the website made them. The TV doesn't add or rename profiles, as on Android TV; it shows them, asks
// for a profile's PIN or the parental PIN where Android would (core's `ProfileRules`), keeps a kids profile to kids and
// family (`KidsContent`), and starts each profile's favourites from the ones the account keeps. Plain TypeScript with
// no tv/core, so Vega's Hermes bundle takes it too; the rules are core/Profiles.kt's, with its cases in the test.
// `@tuned/shared/profiles`.
import { unb64, utf8Encode } from "./bytes.ts";
import type { Item } from "./catalog.ts";
import { searchText } from "./search.ts";

export interface Profile {
    /** Android's id for the profile, as decimal text. */
    readonly id: string;
    readonly name: string;
    /** Which of the seven accents the profile wears (violet, blue, teal, green, orange, pink, red), as an index. */
    readonly colour: number;
    readonly kids: boolean;
    /** The PIN's salted hash (`v1:<salt>:<hash>`), never the PIN; null for none. */
    readonly pinHash: string | null;
    /** A picture's key ("fox", "dragon"), or null for the profile's first letter. */
    readonly avatar: string | null;
    /** Android ids (`Item.androidId`) of the profile's favourites, in the order it keeps them. */
    readonly favourites: readonly string[];
}

export interface AccountProfiles {
    readonly profiles: readonly Profile[];
    /** The parental PIN's hash (Settings › Parental controls on the phone), or null when none is set. */
    readonly parentalPin: string | null;
}

export const NO_PROFILES: AccountProfiles = { profiles: [], parentalPin: null };

/**
 * PBKDF2-HMAC-SHA256 of `password` with `salt`, `bits` long. Handed in, because 120,000 rounds need the device's own
 * crypto: `crypto.subtle` on Tizen (`webPbkdf2`).
 */
export type Pbkdf2 = (password: Uint8Array, salt: Uint8Array, iterations: number, bits: number) => Promise<Uint8Array>;

/** `Pbkdf2` from WebCrypto. */
export function webPbkdf2(subtle: {
    importKey(format: "raw", data: Uint8Array, algorithm: "PBKDF2", extractable: false, usages: string[]): Promise<unknown>;
    deriveBits(
        algorithm: { name: "PBKDF2"; salt: Uint8Array; iterations: number; hash: "SHA-256" },
        key: never,
        length: number,
    ): Promise<ArrayBuffer>;
}): Pbkdf2 {
    return async (password, salt, iterations, bits) => {
        const key = await subtle.importKey("raw", password, "PBKDF2", false, ["deriveBits"]);
        return new Uint8Array(await subtle.deriveBits({ name: "PBKDF2", salt, iterations, hash: "SHA-256" }, key as never, bits));
    };
}

/** A PIN is 4 to 8 digits, as on Android (`PinHash`). */
export const isPin = (pin: string): boolean => /^\d{4,8}$/.test(pin);

/** Whether `pin` matches `stored` (`v1:<salt>:<hash>`), as Android's `PinHash.verify` checks it. */
export async function verifyPin(pbkdf2: Pbkdf2, pin: string, stored: string | null): Promise<boolean> {
    if (!stored || !isPin(pin)) return false;
    const parts = stored.split(":");
    if (parts.length !== 3 || parts[0] !== "v1") return false;
    try {
        const salt = unb64(parts[1]);
        const expected = unb64(parts[2]);
        const got = await pbkdf2(utf8Encode(pin), salt, 120_000, 256);
        if (got.length !== expected.length) return false;
        let diff = 0;
        for (let i = 0; i < got.length; i++) diff |= got[i] ^ expected[i];
        return diff === 0;
    } catch {
        return false;
    }
}

/**
 * JSON with Android's ids kept whole. They're 64-bit numbers, which `JSON.parse` would round to the nearest double
 * and so match nothing; every integer of 16 digits or more outside a string is read as its digits instead.
 */
export function parseWholeIds(text: string): unknown {
    let out = "";
    let from = 0;
    let inString = false;
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (inString) {
            if (c === "\\") i++;
            else if (c === '"') inString = false;
            continue;
        }
        if (c === '"') {
            inString = true;
            continue;
        }
        if (c === "-" || (c >= "0" && c <= "9")) {
            let end = i + 1;
            while (end < text.length && text[end] >= "0" && text[end] <= "9") end++;
            const next = text[end];
            const digits = end - i - (c === "-" ? 1 : 0);
            if (digits >= 16 && next !== "." && next !== "e" && next !== "E") {
                out += `${text.slice(from, i)}"${text.slice(i, end)}"`;
                from = end;
            }
            i = end - 1;
        }
    }
    return JSON.parse(out + text.slice(from));
}

const text = (v: unknown): string | null => (typeof v === "string" ? v : typeof v === "number" ? String(v) : null);

/** The profiles and parental PIN in the account's backup (`GET /api/backup`): Android's sync copy, under `copy`. */
export function profilesFromBackup(backup: string): AccountProfiles {
    let copy: Record<string, unknown>;
    try {
        const parsed = parseWholeIds(backup);
        if (!parsed || typeof parsed !== "object") return NO_PROFILES;
        // Android stores `{fingerprint, changedAt, copy}` (TunedAccount's `Stored`); the sync copy is under `copy`.
        const whole = parsed as Record<string, unknown>;
        copy = whole.copy && typeof whole.copy === "object" ? (whole.copy as Record<string, unknown>) : whole;
    } catch {
        return NO_PROFILES;
    }
    const rows = Array.isArray(copy.profiles) ? (copy.profiles as Record<string, unknown>[]) : [];
    const profiles: Profile[] = [];
    for (const row of rows) {
        if (!row || typeof row !== "object") continue;
        const id = text(row.id);
        if (!id || typeof row.name !== "string") continue;
        const favs = Array.isArray(row.favourites) ? (row.favourites as Record<string, unknown>[]) : [];
        profiles.push({
            id,
            name: row.name,
            colour: typeof row.colour === "number" ? row.colour : 0,
            kids: row.kids === true,
            pinHash: typeof row.pinHash === "string" && row.pinHash ? row.pinHash : null,
            avatar: typeof row.avatar === "string" && row.avatar ? row.avatar : null,
            favourites: favs
                .filter((f) => f && typeof f === "object")
                .sort((a, b) => Number(a.position ?? 0) - Number(b.position ?? 0))
                .map((f) => text(f.itemId))
                .filter((x): x is string => x !== null),
        });
    }
    // In id order, as "Who's watching?" lists them on the phone (ids only grow).
    profiles.sort((a, b) => a.id.length - b.id.length || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    const settings = copy.settings && typeof copy.settings === "object" ? (copy.settings as Record<string, unknown>) : {};
    const pin = settings.adult_pin as { s?: unknown } | undefined;
    return {
        profiles,
        parentalPin: pin && typeof pin.s === "string" && pin.s ? pin.s : null,
    };
}

/** At most six profiles, as on Android (`ProfileRules.MAX_PROFILES`). */
export const MAX_PROFILES = 6;

/**
 * Which PIN stands between the profile in use and `to` (`ProfileRules.lockFor`): a profile with its own PIN always asks
 * for it; leaving a kids profile for a grown-up one asks for the parental PIN, when there is one; moving into a kids
 * profile never asks.
 */
export function lockFor(from: Profile | null, to: Profile, parentalPin: string | null): "none" | "profile" | "parental" {
    if (to.pinHash !== null) return "profile";
    if (from?.kids && !to.kids && parentalPin !== null) return "parental";
    return "none";
}

/** `KidsContent.GROUP_WORDS` and `CHANNEL_WORDS`: channel names leave out "family" and "animation", which match grown-up channels. */
const GROUP_WORDS = ["kid", "child", "cartoon", "family", "junior", "disney", "nick", "baby", "animation", "boomerang"];
const CHANNEL_WORDS = ["kid", "child", "cartoon", "junior", "disney", "nick", "baby", "boomerang"];

/** Whether a stream counts as kids: its group says so, or for a live channel its own name (`KidsContent.isKids`). */
export function isKids(group: string, name: string, live: boolean): boolean {
    const g = group.toLowerCase();
    if (GROUP_WORDS.some((w) => g.includes(w))) return true;
    if (!live) return false;
    const n = searchText(name);
    return CHANNEL_WORDS.some((w) => n.includes(w));
}

/** What a kids profile sees: kids and family groups, and live channels whose names say so; never adult. */
export const forKids = (item: Item): boolean => !item.adult && isKids(item.group, item.name, item.kind === "live");

/** Android's profile pictures (`Avatars`): the free twelve, then the Premium twelve. Keys never change. */
const AVATARS: Record<string, string> = {
    fox: "🦊",
    panda: "🐼",
    tiger: "🐯",
    lion: "🦁",
    frog: "🐸",
    monkey: "🐵",
    penguin: "🐧",
    dog: "🐶",
    cat: "🐱",
    koala: "🐨",
    bear: "🐻",
    rabbit: "🐰",
    dragon: "🐉",
    unicorn: "🦄",
    trex: "🦖",
    owl: "🦉",
    shark: "🦈",
    octopus: "🐙",
    rocket: "🚀",
    alien: "👽",
    robot: "🤖",
    wizard: "🧙",
    crown: "👑",
    diamond: "💎",
};

/** A profile's picture: its emoji, or null to draw its first letter (none, or one this version doesn't know). */
export const avatarGlyph = (profile: Profile): string | null => (profile.avatar ? (AVATARS[profile.avatar] ?? null) : null);

/**
 * Five wrong PINs in a row wait 30 seconds before the next try (`PinThrottle`), so guessing every four-digit PIN takes
 * days. One for the whole TV, so switching between profile and parental PINs doesn't reset it.
 */
export class PinThrottle {
    private wrong = 0;
    private lockedUntil = 0;
    private readonly clock: () => number;

    constructor(clock: () => number = Date.now) {
        this.clock = clock;
    }

    /** True while waiting out a lock: don't even check the PIN. */
    waiting(): boolean {
        return this.clock() < this.lockedUntil;
    }

    /** Counts one answer; call only when `waiting()` is false. */
    record(ok: boolean): "ok" | "wrong" | "wait" {
        if (ok) {
            this.wrong = 0;
            return "ok";
        }
        if (++this.wrong >= 5) {
            this.wrong = 0;
            this.lockedUntil = this.clock() + 30_000;
            return "wait";
        }
        return "wrong";
    }
}

/** The seven accents a profile's colour picks from, as Android draws them (`Accent.swatch`). */
export const PROFILE_COLOURS = ["#5a35f0", "#1b57c4", "#006b5c", "#386a20", "#9a4600", "#b0136e", "#bb1a2c"];

/** Two stops for a profile's circle: its accent lightened and darkened, or a Premium picture's own pair. */
export function profileGradient(profile: Profile): [string, string] {
    const own = PREMIUM_GRADIENTS[profile.avatar ?? ""];
    if (own) return own;
    const n = PROFILE_COLOURS.length;
    const base = PROFILE_COLOURS[((profile.colour % n) + n) % n];
    return [mix(base, 255, 0.22), mix(base, 0, 0.35)];
}

const PREMIUM_GRADIENTS: Record<string, [string, string]> = {
    dragon: ["#34d399", "#065f46"],
    unicorn: ["#f0abfc", "#7c3aed"],
    trex: ["#a3e635", "#3f6212"],
    owl: ["#fbbf24", "#92400e"],
    shark: ["#38bdf8", "#1e3a8a"],
    octopus: ["#fb7185", "#9f1239"],
    rocket: ["#818cf8", "#1e1b4b"],
    alien: ["#2dd4bf", "#134e4a"],
    robot: ["#94a3b8", "#1e293b"],
    wizard: ["#c084fc", "#3b0764"],
    crown: ["#fde68a", "#b45309"],
    diamond: ["#67e8f9", "#0e7490"],
};

function mix(hex: string, toward: number, amount: number): string {
    const parts = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
    return `#${parts
        .map((c) =>
            Math.round(c + (toward - c) * amount)
                .toString(16)
                .padStart(2, "0"),
        )
        .join("")}`;
}

/**
 * A profile's favourites as this TV's item ids, from the Android ids the account keeps: what a profile starts with on
 * a TV that has none of its own yet.
 */
export function favouritesOnTv(profile: Profile, items: readonly Item[]): string[] {
    const wanted = new Set(profile.favourites);
    if (!wanted.size) return [];
    const byAndroid = new Map<string, string>();
    for (const i of items) if (wanted.has(i.androidId)) byAndroid.set(i.androidId, i.id);
    return profile.favourites.map((a) => byAndroid.get(a)).filter((x): x is string => x !== undefined);
}
