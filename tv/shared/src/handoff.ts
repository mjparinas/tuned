// Signing in on a TV from a phone: the TV's half of core/TvHandoff.kt and devices/public/tv/seal.js. The TV makes a
// one-time P-256 key pair and shows a code and a QR link carrying its public key; the phone (the Tuned app, or the
// page at tunedplayer.com/tv) seals what it sends with that key, so the relay only ever holds a box it can't open.
// A box is 1 ‖ sender key (65) ‖ nonce (12) ‖ AES-256-GCM ciphertext and tag; the key is HKDF-SHA256 over the ECDH
// secret (salt: the sender's then the TV's key, info "tuned tv sign-in v1"); the code is the associated data.
import { concat, utf8Encode, utf8Decode } from "./bytes.ts";
import type { CryptoKeyLike, CryptoLike, Gunzip } from "./platform.ts";

export const ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
export const CODE_LENGTH = 8;
export const CODE_SECONDS = 600;
const INFO = "tuned tv sign-in v1";
const CURVE = { name: "ECDH", namedCurve: "P-256" } as const;
const POINT = 65;
const NONCE = 12;

export interface TvKeys {
    /** The uncompressed point, WebCrypto's "raw" form. */
    readonly publicKey: Uint8Array;
    readonly privateKey: CryptoKeyLike;
}

/** Thrown for a box that isn't for this TV, was changed on the way, or was sealed under another code. */
export class NotOpened extends Error {
    constructor(cause?: unknown) {
        super("Not a box for this TV");
        this.name = "NotOpened";
        if (cause !== undefined) (this as { cause?: unknown }).cause = cause;
    }
}

export async function newKeys(crypto: CryptoLike): Promise<TvKeys> {
    const pair = await crypto.subtle.generateKey(CURVE, false, ["deriveBits"]);
    return { publicKey: new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey)), privateKey: pair.privateKey };
}

/** The code as the relay keeps it, from anything typed: any case, spaces or a dash. Null when it can't be one. */
export function normalizeCode(text: string): string | null {
    const code = String(text ?? "")
        .toUpperCase()
        .replace(/[^0-9A-Z]/g, "");
    return code.length === CODE_LENGTH && [...code].every((c) => ALPHABET.includes(c)) ? code : null;
}

/** "K7QF-29MD", easier to read off a TV. */
export const displayCode = (code: string): string => (code.length === CODE_LENGTH ? `${code.slice(0, 4)}-${code.slice(4)}` : code);

/** Four digits from the TV's key, shown on the TV and on the phone when the code was typed rather than scanned. */
export async function checkDigits(publicKey: Uint8Array, crypto: CryptoLike): Promise<string> {
    const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", publicKey));
    const value = (((hash[0] << 24) >>> 0) + (hash[1] << 16) + (hash[2] << 8) + hash[3]) % 10000;
    return String(value).padStart(4, "0");
}

async function hmac(crypto: CryptoLike, key: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
    const k = await crypto.subtle.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    return new Uint8Array(await crypto.subtle.sign("HMAC", k, data));
}

/** HKDF-SHA256 (RFC 5869) to 32 bytes, as TvHandoff.derive: one block of expand. */
async function derive(crypto: CryptoLike, shared: Uint8Array, senderKey: Uint8Array, tvKey: Uint8Array): Promise<Uint8Array> {
    const prk = await hmac(crypto, concat(senderKey, tvKey), shared);
    return hmac(crypto, prk, concat(utf8Encode(INFO), Uint8Array.of(1)));
}

/** Opens a box sealed for `keys` under `code`: the payload's bytes (see `unpack`). Throws NotOpened. */
export async function openBox(box: Uint8Array, code: string, keys: TvKeys, crypto: CryptoLike): Promise<Uint8Array> {
    const head = 1 + POINT + NONCE;
    if (box.length <= head || box[0] !== 1) throw new NotOpened();
    const sender = box.subarray(1, 1 + POINT);
    const nonce = box.subarray(1 + POINT, head);
    try {
        const senderKey = await crypto.subtle.importKey("raw", sender, CURVE, false, []);
        const shared = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: senderKey }, keys.privateKey, 256));
        const raw = await derive(crypto, shared, sender, keys.publicKey);
        const aes = await crypto.subtle.importKey("raw", raw, { name: "AES-GCM" }, false, ["decrypt"]);
        const plain = await crypto.subtle.decrypt(
            { name: "AES-GCM", iv: nonce, additionalData: utf8Encode(code), tagLength: 128 },
            aes,
            box.subarray(head),
        );
        return new Uint8Array(plain);
    } catch (error) {
        throw new NotOpened(error);
    }
}

/** The JSON text a payload holds: its first byte says plain (0) or gzipped (1). */
export async function unpack(payload: Uint8Array, gunzip: Gunzip): Promise<string> {
    const body = payload.subarray(1);
    if (payload[0] === 0) return utf8Decode(body);
    if (payload[0] === 1) {
        try {
            return utf8Decode(await gunzip(body));
        } catch (error) {
            throw new NotOpened(error);
        }
    }
    throw new NotOpened();
}
