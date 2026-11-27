// The WebCrypto calls @tuned/shared's phone sign-in makes (platform.ts `CryptoLike`), done with the pure-JavaScript
// noble libraries, because Vega's Hermes has no WebCrypto ECDH (docs/decisions/vega.md). A key is its raw bytes in a
// small tagged object; nothing here is kept after the code on screen is replaced.
import { gcm } from "@noble/ciphers/aes";
import { p256 } from "@noble/curves/nist";
import { hmac } from "@noble/hashes/hmac";
import { sha256 } from "@noble/hashes/sha2";
import { ungzip } from "pako";
import type { CryptoKeyLike, CryptoLike, Gunzip, SubtleLike } from "@tuned/shared/platform";

type Key = { readonly use: "public" | "private" | "hmac" | "aes"; readonly bytes: Uint8Array };

const key = (k: CryptoKeyLike, use: Key["use"]): Uint8Array => {
    const found = k as Key;
    if (found?.use !== use) throw new Error(`Not a ${use} key`);
    return found.bytes;
};

const buffer = (bytes: Uint8Array): ArrayBuffer => bytes.slice().buffer as ArrayBuffer;

/** `random(n)` gives n random bytes from the stick (platform/random.ts). */
export function nobleCrypto(random: (n: number) => Uint8Array): CryptoLike {
    const subtle: SubtleLike = {
        async generateKey() {
            const secret = p256.utils.randomSecretKey(random(48));
            return {
                publicKey: { use: "public", bytes: p256.getPublicKey(secret, false) } satisfies Key,
                privateKey: { use: "private", bytes: secret } satisfies Key,
            };
        },
        async exportKey(_format, k) {
            return buffer(key(k, "public"));
        },
        async importKey(_format, data, algorithm) {
            if (algorithm.name === "ECDH") {
                if (!p256.utils.isValidPublicKey(data, false)) throw new Error("Not a P-256 key");
                return { use: "public", bytes: data.slice() } satisfies Key;
            }
            return { use: algorithm.name === "HMAC" ? "hmac" : "aes", bytes: data.slice() } satisfies Key;
        },
        async deriveBits(algorithm, k) {
            // The x coordinate, as WebCrypto's ECDH gives it.
            return buffer(p256.getSharedSecret(key(k, "private"), key(algorithm.public, "public"), true).slice(1));
        },
        async sign(_algorithm, k, data) {
            return buffer(hmac(sha256, key(k, "hmac"), data));
        },
        async decrypt(algorithm, k, data) {
            return buffer(gcm(key(k, "aes"), algorithm.iv, algorithm.additionalData).decrypt(data));
        },
        async digest(_algorithm, data) {
            return buffer(sha256(data));
        },
    };
    return {
        subtle,
        getRandomValues<T extends Uint8Array>(array: T): T {
            array.set(random(array.length));
            return array;
        },
    };
}

export const gunzip: Gunzip = async (bytes) => ungzip(bytes);
