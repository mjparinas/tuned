// Two-step sign-in for the admin (as in Liftsmith): RFC 6238 codes from an authenticator app, 30-second steps, one
// step of drift either way, and a step never accepted twice. Plus single-use recovery codes, stored only hashed.
import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";

const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
export const STEP_SECONDS = 30;

export function base32(bytes) {
    let bits = 0;
    let value = 0;
    let out = "";
    for (const byte of bytes) {
        value = (value << 8) | byte;
        bits += 8;
        while (bits >= 5) {
            out += B32[(value >>> (bits - 5)) & 31];
            bits -= 5;
        }
    }
    if (bits > 0) out += B32[(value << (5 - bits)) & 31];
    return out;
}

export function fromBase32(text) {
    const clean = String(text).toUpperCase().replace(/[^A-Z2-7]/g, "");
    let bits = 0;
    let value = 0;
    const out = [];
    for (const char of clean) {
        value = (value << 5) | B32.indexOf(char);
        bits += 5;
        if (bits >= 8) {
            out.push((value >>> (bits - 8)) & 255);
            bits -= 8;
        }
    }
    return Buffer.from(out);
}

export const newSecret = (random = randomBytes) => base32(random(20));

/** The 6-digit code for one step (RFC 4226 truncation over HMAC-SHA1). */
export function codeAt(secret, step) {
    const counter = Buffer.alloc(8);
    counter.writeBigUInt64BE(BigInt(step));
    const mac = createHmac("sha1", fromBase32(secret)).update(counter).digest();
    const offset = mac[mac.length - 1] & 15;
    const number = (mac.readUInt32BE(offset) & 0x7fffffff) % 1_000_000;
    return String(number).padStart(6, "0");
}

export const stepOf = (nowMs) => Math.floor(nowMs / 1000 / STEP_SECONDS);

/**
 * The step a typed code matches, or null. `lastStep` is the step accepted last time, so a code can't be used twice
 * and an older one can't follow a newer one.
 */
export function verify(secret, typed, nowMs, lastStep = -1) {
    const code = String(typed ?? "").replace(/\s/g, "");
    if (!/^\d{6}$/.test(code)) return null;
    const now = stepOf(nowMs);
    for (const step of [now - 1, now, now + 1]) {
        if (step <= lastStep) continue;
        const want = Buffer.from(codeAt(secret, step));
        if (timingSafeEqual(want, Buffer.from(code))) return step;
    }
    return null;
}

/** The address an authenticator app reads from a QR code or a pasted link. */
export const otpauth = (secret, email) =>
    `otpauth://totp/Tuned%20admin:${encodeURIComponent(email)}?secret=${secret}&issuer=Tuned%20admin&digits=6&period=${STEP_SECONDS}`;

const RECOVERY = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
export const hashRecovery = (code) => createHash("sha256").update(String(code).toUpperCase().replace(/[^A-Z0-9]/g, "")).digest("hex");

/** Eight codes like `K7QM-2XPA`: shown once, kept only as hashes. */
export function newRecoveryCodes(random = randomBytes) {
    return Array.from({ length: 8 }, () => {
        const bytes = random(8);
        const chars = [...bytes].map((b) => RECOVERY[b % RECOVERY.length]).join("");
        return `${chars.slice(0, 4)}-${chars.slice(4)}`;
    });
}

/** The hashes left after using `typed`, or null when it isn't one of them. */
export function useRecovery(hashes, typed) {
    const hash = hashRecovery(typed);
    if (!hashes.includes(hash)) return null;
    return hashes.filter((h) => h !== hash);
}
