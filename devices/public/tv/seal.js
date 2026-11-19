// Sealing for signing in on a TV from a phone (docs/decisions/onboarding.md). The same format as
// core/TvHandoff.kt: ECDH on P-256 with a fresh key, HKDF-SHA256 (salt: sender key then TV key) to AES-256-GCM,
// the code as associated data. A box is 1 ‖ sender key (65) ‖ nonce (12) ‖ ciphertext and tag. Runs in the phone's
// browser and in Node's tests, with WebCrypto only.

export const ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
export const CODE_LENGTH = 8;
const INFO = "tuned tv sign-in v1";
const CURVE = { name: "ECDH", namedCurve: "P-256" };
const text = new TextEncoder();

const concat = (...parts) => {
    const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
    let at = 0;
    for (const p of parts) {
        out.set(p, at);
        at += p.length;
    }
    return out;
};

export function b64(bytes) {
    let binary = "";
    for (const b of bytes) binary += String.fromCharCode(b);
    return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function unb64(value) {
    const binary = atob(value.replace(/-/g, "+").replace(/_/g, "/"));
    return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

/** The code as the relay keeps it, from what someone typed; null when it can't be one. */
export function normalizeCode(value) {
    const code = String(value ?? "")
        .toUpperCase()
        .replace(/[^0-9A-Z]/g, "");
    return code.length === CODE_LENGTH && [...code].every((c) => ALPHABET.includes(c)) ? code : null;
}

/** The code and TV key from a link's fragment (`#CODE.KEY`), or null. The key is checked when it's imported. */
export function parseFragment(hash) {
    const [rawCode, rawKey] = String(hash ?? "")
        .replace(/^#/, "")
        .split(".");
    const code = normalizeCode(rawCode);
    if (!code || !rawKey) return null;
    try {
        const key = unb64(rawKey);
        return key.length === 65 && key[0] === 4 ? { code, key } : null;
    } catch {
        return null;
    }
}

/** Four digits from the TV's key, shown on both screens when the code was typed rather than scanned. */
export async function checkDigits(key) {
    const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", key));
    const value = new DataView(hash.buffer).getUint32(0) % 10000;
    return String(value).padStart(4, "0");
}

async function aesKey(shared, senderKey, tvKey, use) {
    const base = await crypto.subtle.importKey("raw", shared, "HKDF", false, ["deriveKey"]);
    return crypto.subtle.deriveKey(
        { name: "HKDF", hash: "SHA-256", salt: concat(senderKey, tvKey), info: text.encode(INFO) },
        base,
        { name: "AES-GCM", length: 256 },
        false,
        [use],
    );
}

/** JSON as the TV expects it: a first byte of 0 says it isn't gzipped. */
export const pack = (json) => concat(Uint8Array.of(0), text.encode(json));

/** Seals `plain` (bytes) for the TV with raw public key `tvKey` under `code`. */
export async function seal(plain, code, tvKey) {
    const tv = await crypto.subtle.importKey("raw", tvKey, CURVE, false, []);
    const mine = await crypto.subtle.generateKey(CURVE, true, ["deriveBits"]);
    const senderKey = new Uint8Array(await crypto.subtle.exportKey("raw", mine.publicKey));
    const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: tv }, mine.privateKey, 256);
    const key = await aesKey(shared, senderKey, tvKey, "encrypt");
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const sealed = await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce, additionalData: text.encode(code) }, key, plain);
    return concat(Uint8Array.of(1), senderKey, nonce, new Uint8Array(sealed));
}

/** Opens a box with the TV's private key (a CryptoKey) and raw public key; for tests, and a TV made of web pages later. */
export async function open(box, code, privateKey, tvKey) {
    if (box[0] !== 1 || box.length <= 78) throw new Error("Not a box for this TV");
    const senderKey = box.slice(1, 66);
    const sender = await crypto.subtle.importKey("raw", senderKey, CURVE, false, []);
    const shared = await crypto.subtle.deriveBits({ name: "ECDH", public: sender }, privateKey, 256);
    const key = await aesKey(shared, senderKey, tvKey, "decrypt");
    const plain = await crypto.subtle.decrypt(
        { name: "AES-GCM", iv: box.slice(66, 78), additionalData: text.encode(code) },
        key,
        box.slice(78),
    );
    return new Uint8Array(plain);
}
