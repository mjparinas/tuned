// UTF-8 and base64 by hand: Hermes (Vega) has no TextDecoder or atob everywhere, and these are small.

export function utf8Encode(text: string): Uint8Array {
    const out: number[] = [];
    for (let i = 0; i < text.length; i++) {
        let c = text.charCodeAt(i);
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < text.length) {
            const low = text.charCodeAt(i + 1);
            if (low >= 0xdc00 && low <= 0xdfff) {
                c = 0x10000 + ((c - 0xd800) << 10) + (low - 0xdc00);
                i++;
            }
        }
        if (c < 0x80) out.push(c);
        else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
        else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
        else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
    return Uint8Array.from(out);
}

/**
 * Decodes UTF-8 that arrives in pieces: a character split between two pieces waits for the second. Broken bytes
 * become U+FFFD, as TextDecoder does.
 */
export class Utf8Stream {
    private pending: number[] = [];

    decode(bytes: Uint8Array, last = false): string {
        const data = this.pending.length ? Uint8Array.from([...this.pending, ...bytes]) : bytes;
        this.pending = [];
        let out = "";
        let chunk: number[] = [];
        let i = 0;
        while (i < data.length) {
            const b = data[i];
            const need = b < 0x80 ? 0 : b >= 0xf0 && b < 0xf8 ? 3 : b >= 0xe0 ? 2 : b >= 0xc0 ? 1 : -1;
            if (need < 0) {
                chunk.push(0xfffd);
                i++;
            } else if (need > 0 && i + need >= data.length && !last) {
                this.pending = Array.from(data.subarray(i));
                break;
            } else {
                let code = need === 0 ? b : b & (0x3f >> need);
                let ok = true;
                for (let k = 1; k <= need; k++) {
                    const next = data[i + k];
                    if (next === undefined || (next & 0xc0) !== 0x80) {
                        ok = false;
                        break;
                    }
                    code = (code << 6) | (next & 63);
                }
                if (!ok) {
                    chunk.push(0xfffd);
                    i++;
                    continue;
                }
                if (code >= 0x10000) {
                    code -= 0x10000;
                    chunk.push(0xd800 + (code >> 10), 0xdc00 + (code & 1023));
                } else chunk.push(code);
                i += need + 1;
            }
            if (chunk.length > 8192) {
                out += String.fromCharCode(...chunk);
                chunk = [];
            }
        }
        return out + String.fromCharCode(...chunk);
    }
}

export const utf8Decode = (bytes: Uint8Array): string => new Utf8Stream().decode(bytes, true);

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/** Base64url without padding, as the relay and TvHandoff write it. */
export function b64(bytes: Uint8Array): string {
    let out = "";
    for (let i = 0; i < bytes.length; i += 3) {
        const n = (bytes[i] << 16) | ((bytes[i + 1] ?? 0) << 8) | (bytes[i + 2] ?? 0);
        out += ALPHABET[(n >> 18) & 63] + ALPHABET[(n >> 12) & 63];
        if (i + 1 < bytes.length) out += ALPHABET[(n >> 6) & 63];
        if (i + 2 < bytes.length) out += ALPHABET[n & 63];
    }
    return out;
}

/** Reads base64url or plain base64, padded or not. Throws on anything else. */
export function unb64(text: string): Uint8Array {
    const clean = text.replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
    const out: number[] = [];
    let bits = 0;
    let value = 0;
    for (const c of clean) {
        const n = ALPHABET.indexOf(c);
        if (n < 0) throw new Error("Not base64");
        value = (value << 6) | n;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push((value >> bits) & 255);
        }
    }
    return Uint8Array.from(out);
}

export function concat(...parts: Uint8Array[]): Uint8Array {
    const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
    let at = 0;
    for (const p of parts) {
        out.set(p, at);
        at += p.length;
    }
    return out;
}

export const hex = (bytes: Uint8Array): string => Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
