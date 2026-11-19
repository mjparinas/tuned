import { test } from "node:test";
import assert from "node:assert/strict";
import { ALPHABET, newCode, normalizeCode, tvLinkKey, tvRelay, validBox, validKey } from "./tv.mjs";
import * as page from "../public/tv/seal.js";

/** Enough of Redis for the relay: strings with expiry, counters. */
function fakeRedis() {
    const data = new Map();
    let now = 0;
    const live = (key) => {
        const item = data.get(key);
        if (item && item.until <= now) data.delete(key);
        return data.get(key);
    };
    return {
        tick: (seconds) => (now += seconds * 1000),
        async command(name, key, ...rest) {
            switch (name) {
                case "GET":
                    return live(key)?.value ?? null;
                case "GETDEL": {
                    const value = live(key)?.value ?? null;
                    data.delete(key);
                    return value;
                }
                case "DEL":
                    return data.delete(key) ? 1 : 0;
                case "SET": {
                    const [value, , seconds, nx] = rest;
                    if (nx === "NX" && live(key)) return null;
                    data.set(key, { value, until: now + seconds * 1000 });
                    return "OK";
                }
                case "INCR": {
                    const item = live(key) ?? { value: 0, until: Infinity };
                    item.value += 1;
                    data.set(key, item);
                    return item.value;
                }
                case "EXPIRE":
                    data.get(key).until = now + rest[0] * 1000;
                    return 1;
                case "TTL": {
                    const item = live(key);
                    return item ? Math.ceil((item.until - now) / 1000) : -2;
                }
                default:
                    throw new Error(`no ${name}`);
            }
        },
    };
}

const post = (body, ip = "1.2.3.4") =>
    new Request("https://relay.test/api", {
        method: "POST",
        headers: { "x-forwarded-for": ip, "content-type": "application/json" },
        body: JSON.stringify(body),
    });

async function tvKeys() {
    const pair = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
    const raw = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey));
    return { pair, raw, key: page.b64(raw) };
}

test("codes use the alphabet and read back however they're typed", () => {
    for (let i = 0; i < 50; i++) {
        const code = newCode();
        assert.equal(code.length, 8);
        assert.ok([...code].every((c) => ALPHABET.includes(c)));
        assert.equal(normalizeCode(`${code.slice(0, 4).toLowerCase()} - ${code.slice(4)}`), code);
    }
    assert.equal(normalizeCode("K7QF-29M0"), null, "0 is never in a code");
    assert.equal(normalizeCode("K7QF-29M"), null);
    assert.equal(page.normalizeCode("k7qf 29md"), "K7QF29MD");
});

test("a key and a box are checked before anything is kept", async () => {
    const { key } = await tvKeys();
    assert.ok(validKey(key));
    assert.ok(!validKey(key.slice(1)));
    assert.ok(!validKey("A".repeat(87)), "not an uncompressed point");
    assert.ok(!validBox("short"));
    assert.ok(!validBox("x".repeat(800_000)));
});

test("the TV takes what the phone sealed, once, and the relay can't read it", async () => {
    const redis = fakeRedis();
    const relay = tvRelay(redis);
    const tv = await tvKeys();
    const made = await (await relay.code(post({ key: tv.key }))).json();
    assert.equal(made.status, "ok");

    assert.equal((await (await relay.take(post({ code: made.code, secret: made.secret }))).json()).status, "waiting");

    const plain = page.pack(
        JSON.stringify({ version: 1, playlists: [{ server: "http://tv.example.com", username: "me", password: "pw" }] }),
    );
    const box = page.b64(await page.seal(plain, made.code, tv.raw));
    assert.equal((await relay.send(post({ code: made.code, box }))).status, 200);
    assert.equal((await relay.send(post({ code: made.code, box }))).status, 409, "one box per code");

    assert.equal((await relay.take(post({ code: made.code, secret: "wrong" }))).status, 404, "only the TV takes it");
    const taken = await (await relay.take(post({ code: made.code, secret: made.secret }))).json();
    assert.equal(taken.status, "ok");
    const opened = await page.open(page.unb64(taken.box), made.code, tv.pair.privateKey, tv.raw);
    assert.deepEqual(opened, plain);
    assert.equal((await relay.take(post({ code: made.code, secret: made.secret }))).status, 404, "the code ends once taken");
});

test("an account sign-in from the website reaches the TV once, and ends the code", async () => {
    const redis = fakeRedis();
    const relay = tvRelay(redis);
    const tv = await tvKeys();
    const made = await (await relay.code(post({ key: tv.key }))).json();
    await redis.command("SET", tvLinkKey(made.code), "one-time-link", "EX", 600, "NX");
    const taken = await (await relay.take(post({ code: made.code, secret: made.secret }))).json();
    assert.deepEqual(taken, { status: "ok", box: null, link: "one-time-link" });
    assert.equal((await relay.take(post({ code: made.code, secret: made.secret }))).status, 404);
});

test("the typed-code path hands over the TV's key, and both screens show the same check", async () => {
    const relay = tvRelay(fakeRedis());
    const tv = await tvKeys();
    const made = await (await relay.code(post({ key: tv.key }))).json();
    const found = await (await relay.key(post({ code: made.code.toLowerCase() }))).json();
    assert.equal(found.key, tv.key);
    assert.match(await page.checkDigits(tv.raw), /^\d{4}$/);
    assert.equal((await relay.key(post({ code: "22222222" }))).status, 404);
});

test("codes run out after ten minutes", async () => {
    const redis = fakeRedis();
    const relay = tvRelay(redis);
    const tv = await tvKeys();
    const made = await (await relay.code(post({ key: tv.key }))).json();
    redis.tick(601);
    const box = page.b64(await page.seal(page.pack("{}"), made.code, tv.raw));
    assert.equal((await relay.send(post({ code: made.code, box }))).status, 404);
    assert.equal((await relay.take(post({ code: made.code, secret: made.secret }))).status, 404);
});

test("each visitor gets a few codes and lookups an hour", async () => {
    const relay = tvRelay(fakeRedis());
    for (let i = 0; i < 20; i++) assert.equal((await relay.key(post({ code: "22222222" }))).status, 404);
    assert.equal((await relay.key(post({ code: "22222222" }))).status, 429);
    assert.equal((await relay.key(post({ code: "22222222" }, "5.6.7.8"))).status, 404, "another visitor isn't held up");
});

test("a box sealed for another code doesn't open", async () => {
    const tv = await tvKeys();
    const box = await page.seal(page.pack("{}"), "K7QF29MD", tv.raw);
    await assert.rejects(page.open(box, "K7QF29ME", tv.pair.privateKey, tv.raw));
    box[box.length - 1] ^= 1;
    await assert.rejects(page.open(box, "K7QF29MD", tv.pair.privateKey, tv.raw));
});

test("a broken Redis answers 503, which the TV takes as try again", async () => {
    const relay = tvRelay({
        command: async () => {
            throw new Error("down");
        },
    });
    const tv = await tvKeys();
    const original = console.error;
    console.error = () => {};
    try {
        assert.equal((await relay.code(post({ key: tv.key }))).status, 503);
    } finally {
        console.error = original;
    }
});
