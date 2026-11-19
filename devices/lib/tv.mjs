// The relay for signing in on a TV from a phone (docs/decisions/onboarding.md). The TV registers a one-time public
// key and gets a short code; the phone posts a box sealed with that key; the TV takes it once. Only the key, a hash
// of the TV's poll secret and the sealed box are kept, under a hash of the code, and only for ten minutes. Nothing
// here can open a box.
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";

export const ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
export const CODE_LENGTH = 8;
export const CODE_SECONDS = 600;
/** A sealed box of 512 KB, in base64. */
export const MAX_BOX = Math.ceil((512 * 1024 * 4) / 3);
const LIMITS = { code: 30, key: 20, send: 20 };

const sha = (value) => createHash("sha256").update(value).digest("hex");
const json = (body, status = 200) => Response.json(body, { status, headers: { "Cache-Control": "no-store" } });

/** A new code from ALPHABET, without bias: bytes past the last whole run of the alphabet are thrown away. */
export function newCode(random = randomBytes) {
    const limit = 256 - (256 % ALPHABET.length);
    let code = "";
    while (code.length < CODE_LENGTH) {
        for (const byte of random(16)) {
            if (byte < limit && code.length < CODE_LENGTH) code += ALPHABET[byte % ALPHABET.length];
        }
    }
    return code;
}

export function normalizeCode(value) {
    const code = String(value ?? "")
        .toUpperCase()
        .replace(/[^0-9A-Z]/g, "");
    return code.length === CODE_LENGTH && [...code].every((c) => ALPHABET.includes(c)) ? code : null;
}

const B64 = /^[A-Za-z0-9_-]+$/;
/** An uncompressed P-256 point in base64url: 65 bytes starting with 4. The TV checks it's on the curve. */
export const validKey = (key) => typeof key === "string" && key.length === 87 && B64.test(key) && Buffer.from(key, "base64url")[0] === 4;
export const validBox = (box) => typeof box === "string" && box.length > 104 && box.length <= MAX_BOX && B64.test(box);

const entryKey = (code) => `tv:${sha(code)}`;
const boxKey = (code) => `tvbox:${sha(code)}`;

async function body(request) {
    try {
        return await request.json();
    } catch {
        return null;
    }
}

/** The four endpoints, over `redis` (`command(...args)`), so tests can hand in a stand-in. */
export function tvRelay(redis) {
    /** A visitor's uses of one endpoint this hour; the address is kept only hashed, and only for the hour. */
    async function overLimit(request, what) {
        const ip = (request.headers.get("x-forwarded-for") ?? "").split(",")[0].trim();
        const key = `tvlimit:${what}:${sha(ip)}`;
        const count = await redis.command("INCR", key);
        if (count === 1) await redis.command("EXPIRE", key, 3600);
        return count > LIMITS[what];
    }

    async function entry(code) {
        const raw = await redis.command("GET", entryKey(code));
        return raw ? JSON.parse(raw) : null;
    }

    async function guarded(work) {
        try {
            return await work();
        } catch (error) {
            console.error(error);
            return json({ status: "unavailable" }, 503);
        }
    }

    return {
        /** POST { key } from the TV: a code and the secret the TV polls with. */
        code: (request) =>
            guarded(async () => {
                const ask = await body(request);
                if (!validKey(ask?.key)) return json({ status: "bad request" }, 400);
                if (await overLimit(request, "code")) return json({ status: "too many" }, 429);
                const secret = randomBytes(32).toString("hex");
                for (let tries = 0; tries < 5; tries++) {
                    const code = newCode();
                    const saved = await redis.command(
                        "SET",
                        entryKey(code),
                        JSON.stringify({ key: ask.key, secret: sha(secret) }),
                        "EX",
                        CODE_SECONDS,
                        "NX",
                    );
                    if (saved === "OK") return json({ status: "ok", code, secret, seconds: CODE_SECONDS });
                }
                return json({ status: "unavailable" }, 503);
            }),

        /** POST { code } from the phone page when the code was typed: the TV's key, to check against the TV. */
        key: (request) =>
            guarded(async () => {
                const code = normalizeCode((await body(request))?.code);
                if (!code) return json({ status: "bad code" }, 400);
                if (await overLimit(request, "key")) return json({ status: "too many" }, 429);
                const found = await entry(code);
                return found ? json({ status: "ok", key: found.key }) : json({ status: "not found" }, 404);
            }),

        /** POST { code, box } from the phone: one sealed box per code, kept until the code runs out. */
        send: (request) =>
            guarded(async () => {
                const ask = await body(request);
                const code = normalizeCode(ask?.code);
                if (!code || !validBox(ask?.box)) return json({ status: "bad request" }, 400);
                if (await overLimit(request, "send")) return json({ status: "too many" }, 429);
                const left = await redis.command("TTL", entryKey(code));
                if (!(left > 0)) return json({ status: "not found" }, 404);
                const saved = await redis.command("SET", boxKey(code), ask.box, "EX", left, "NX");
                return saved === "OK" ? json({ status: "ok" }) : json({ status: "already sent" }, 409);
            }),

        /** POST { code, secret } from the TV, every few seconds: the box once it's there, which also ends the code. */
        take: (request) =>
            guarded(async () => {
                const ask = await body(request);
                const code = normalizeCode(ask?.code);
                if (!code || typeof ask?.secret !== "string") return json({ status: "bad request" }, 400);
                const found = await entry(code);
                if (!found) return json({ status: "not found" }, 404);
                const given = Buffer.from(sha(ask.secret));
                if (!timingSafeEqual(given, Buffer.from(found.secret))) return json({ status: "not found" }, 404);
                const box = await redis.command("GETDEL", boxKey(code));
                if (!box) return json({ status: "waiting" });
                await redis.command("DEL", entryKey(code));
                return json({ status: "ok", box });
            }),
    };
}
