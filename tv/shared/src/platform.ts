// What tv/shared needs from the app it runs in, handed in rather than found on `globalThis`, so the same code runs in
// Tizen's browser (Chromium 69 on 2020 TVs) and on Vega's Hermes, which have different networking, storage and crypto.

/** The part of a `fetch` answer tv/shared reads. A streaming `body` is used when there is one (Tizen); Vega has none. */
export interface HttpResponse {
    readonly ok: boolean;
    readonly status: number;
    readonly headers: { get(name: string): string | null };
    text(): Promise<string>;
    json(): Promise<unknown>;
    readonly body?: { getReader(): { read(): Promise<{ done: boolean; value?: Uint8Array }>; cancel(): Promise<void> } } | null;
}

export interface HttpInit {
    method?: string;
    headers?: Record<string, string>;
    body?: string;
    signal?: AbortSignalLike;
}

export interface AbortSignalLike {
    readonly aborted: boolean;
}

/** `fetch`, or anything shaped like it. */
export type Fetch = (url: string, init?: HttpInit) => Promise<HttpResponse>;

/** Strings by key, kept on the device: IndexedDB or localStorage on Tizen, the key-value store on Vega. */
export interface KeyValueStore {
    get(key: string): Promise<string | null>;
    set(key: string, value: string): Promise<void>;
    remove(key: string): Promise<void>;
}

/** A store in memory, for tests and as a last resort when the device's store fails. */
export class MemoryStore implements KeyValueStore {
    private readonly values = new Map<string, string>();

    async get(key: string): Promise<string | null> {
        return this.values.get(key) ?? null;
    }

    async set(key: string, value: string): Promise<void> {
        this.values.set(key, value);
    }

    async remove(key: string): Promise<void> {
        this.values.delete(key);
    }
}

/** An opaque WebCrypto key. */
export type CryptoKeyLike = object;

/**
 * The WebCrypto calls the phone sign-in needs (`crypto.subtle` on Tizen; a wrapper over noble on Vega): ECDH on
 * P-256, HMAC-SHA256, AES-256-GCM and SHA-256. HKDF is done here with HMAC, so it isn't needed.
 */
export interface SubtleLike {
    generateKey(
        algorithm: { name: "ECDH"; namedCurve: "P-256" },
        extractable: boolean,
        usages: string[],
    ): Promise<{ publicKey: CryptoKeyLike; privateKey: CryptoKeyLike }>;
    exportKey(format: "raw", key: CryptoKeyLike): Promise<ArrayBuffer>;
    importKey(
        format: "raw",
        data: Uint8Array,
        algorithm: { name: "ECDH"; namedCurve: "P-256" } | { name: "HMAC"; hash: "SHA-256" } | { name: "AES-GCM" },
        extractable: boolean,
        usages: string[],
    ): Promise<CryptoKeyLike>;
    deriveBits(algorithm: { name: "ECDH"; public: CryptoKeyLike }, key: CryptoKeyLike, length: number): Promise<ArrayBuffer>;
    sign(algorithm: "HMAC", key: CryptoKeyLike, data: Uint8Array): Promise<ArrayBuffer>;
    decrypt(
        algorithm: { name: "AES-GCM"; iv: Uint8Array; additionalData: Uint8Array; tagLength: 128 },
        key: CryptoKeyLike,
        data: Uint8Array,
    ): Promise<ArrayBuffer>;
    digest(algorithm: "SHA-256", data: Uint8Array): Promise<ArrayBuffer>;
}

export interface CryptoLike {
    readonly subtle: SubtleLike;
    getRandomValues<T extends Uint8Array>(array: T): T;
}

/** Unzips gzip bytes: `DecompressionStream` on newer TVs, pako elsewhere. */
export type Gunzip = (bytes: Uint8Array) => Promise<Uint8Array>;

/** Writes a line to the app's log. tv/shared never passes it a login; links go through `redact` first. */
export type Log = (tag: string, message: string) => void;
