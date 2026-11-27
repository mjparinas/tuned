// Signing in on a TV from a phone (docs/decisions/onboarding.md), in the format of core/TvHandoff.kt and
// devices/public/tv/seal.js: ECDH on P-256, HKDF-SHA256 (salt: sender key then TV key, info "tuned tv sign-in v1") to
// AES-256-GCM with the code as associated data; a box is 1 ‖ sender key (65) ‖ nonce (12) ‖ ciphertext and tag.
// Vega's Hermes has no WebCrypto ECDH, so this uses the pure-JavaScript noble libraries (docs/decisions/vega.md).
import { gcm } from "@noble/ciphers/aes";
import { p256 } from "@noble/curves/nist";
import { hkdf } from "@noble/hashes/hkdf";
import { sha256 } from "@noble/hashes/sha2";
import { ungzip } from "pako";
import type { Login } from "./models";

export const ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
export const CODE_LENGTH = 8;
const INFO = new TextEncoder().encode("tuned tv sign-in v1");
const POINT = 65;
const NONCE = 12;

export interface Keys {
    publicKey: Uint8Array;
    privateKey: Uint8Array;
}

export class NotOpened extends Error {
    constructor() {
        super("Not a box for this TV");
        this.name = "NotOpened";
    }
}

/** A one-time key pair, from `random` (the stick's own random source). */
export function newKeys(random: (n: number) => Uint8Array): Keys {
    const privateKey = p256.utils.randomSecretKey(random(48));
    return { privateKey, publicKey: p256.getPublicKey(privateKey, false) };
}

export function b64(bytes: Uint8Array): string {
    let binary = "";
    for (const b of bytes) binary += String.fromCharCode(b);
    return globalThis.btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function unb64(text: string): Uint8Array {
    const binary = globalThis.atob(text.replace(/-/g, "+").replace(/_/g, "/"));
    return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

export const displayCode = (code: string) => (code.length === CODE_LENGTH ? `${code.slice(0, 4)}-${code.slice(4)}` : code);

/** The QR link: the phone page, with the code and the key after `#`. */
export const link = (base: string, code: string, publicKey: Uint8Array) => `${base.replace(/\/+$/, "")}/tv#${code}.${b64(publicKey)}`;

/** Four digits from the key, shown on both screens when the code was typed rather than scanned. */
export function checkDigits(publicKey: Uint8Array): string {
    const hash = sha256(publicKey);
    const value = (((hash[0] << 24) >>> 0) + (hash[1] << 16) + (hash[2] << 8) + hash[3]) % 10000;
    return String(value).padStart(4, "0");
}

/** Opens a box sealed for `keys` under `code`, or throws `NotOpened`. */
export function open(box: Uint8Array, code: string, keys: Keys): Uint8Array {
    const head = 1 + POINT + NONCE;
    if (box.length <= head || box[0] !== 1) throw new NotOpened();
    const sender = box.slice(1, 1 + POINT);
    const nonce = box.slice(1 + POINT, head);
    try {
        if (!p256.utils.isValidPublicKey(sender, false)) throw new NotOpened();
        const shared = p256.getSharedSecret(keys.privateKey, sender, true).slice(1);
        const salt = new Uint8Array(POINT * 2);
        salt.set(sender, 0);
        salt.set(keys.publicKey, POINT);
        const key = hkdf(sha256, shared, salt, INFO, 32);
        return gcm(key, nonce, new TextEncoder().encode(code)).decrypt(box.slice(head));
    } catch {
        throw new NotOpened();
    }
}

/** The JSON a box holds: a first byte of 0 for plain, 1 for gzipped (a sync copy from the Android app). */
export function unpack(payload: Uint8Array): unknown {
    const body = payload.slice(1);
    let bytes: Uint8Array;
    if (payload[0] === 0) bytes = body;
    else if (payload[0] === 1) bytes = ungzip(body);
    else throw new NotOpened();
    return JSON.parse(new TextDecoder().decode(bytes));
}

/** The first playlist the phone sent, as a login the stick can use. */
export function loginFrom(json: any): Login | null {
    const first = Array.isArray(json?.playlists) ? json.playlists[0] : null;
    if (!first) return null;
    const name = typeof first.name === "string" && first.name.trim() ? first.name.trim() : undefined;
    if (first.server && first.username && first.password) {
        return { kind: "xtream", server: String(first.server), username: String(first.username), password: String(first.password), name };
    }
    if (first.playlistUrl) {
        return {
            kind: "link",
            playlistUrl: String(first.playlistUrl),
            guideUrl: first.guideUrl ? String(first.guideUrl) : undefined,
            name,
        };
    }
    return null;
}
