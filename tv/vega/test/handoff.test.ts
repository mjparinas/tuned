import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import path from "node:path";
import { gzipSync } from "node:zlib";
import { b64, unb64 } from "@tuned/shared/bytes";
import { checkDigits, newKeys, NotOpened, openBox, unpack } from "@tuned/shared/handoff";
import { fromHandoff } from "@tuned/shared/login";
import { gunzip, nobleCrypto } from "../src/platform/crypto";

// @tuned/shared's phone sign-in, run on the noble stand-in for WebCrypto that the stick uses, against the phone page.
const crypto = nobleCrypto((n) => new Uint8Array(randomBytes(n)));
const sealWithPhonePage = (code: string, key: Uint8Array, json: string) =>
    unb64(execFileSync("node", [path.join(__dirname, "seal-fixture.mjs"), code, b64(key), json], { encoding: "utf8" }));
const open = async (box: Uint8Array, code: string, keys: Awaited<ReturnType<typeof newKeys>>) =>
    fromHandoff(await unpack(await openBox(box, code, keys, crypto), gunzip));

describe("phone sign-in on the stick's crypto", () => {
    const json = JSON.stringify({
        version: 1,
        playlists: [{ server: "http://tv.example:8080", username: "anna", password: "s3cret", name: "Home" }],
    });

    it("makes an uncompressed P-256 key the relay accepts", async () => {
        const keys = await newKeys(crypto);
        expect(keys.publicKey.length).toBe(65);
        expect(keys.publicKey[0]).toBe(4);
        expect(b64(keys.publicKey)).toHaveLength(87);
        expect(await checkDigits(keys.publicKey, crypto)).toMatch(/^\d{4}$/);
    });

    it("opens a box sealed by the phone page", async () => {
        const keys = await newKeys(crypto);
        const box = sealWithPhonePage("K7QF29MD", keys.publicKey, json);
        expect(await open(box, "K7QF29MD", keys)).toEqual([
            { kind: "xtream", server: "http://tv.example:8080", username: "anna", password: "s3cret", name: "Home" },
        ]);
    });

    it("refuses a box for another code or another TV, or one changed on the way", async () => {
        const keys = await newKeys(crypto);
        const box = sealWithPhonePage("K7QF29MD", keys.publicKey, json);
        await expect(openBox(box, "AAAAAAAA", keys, crypto)).rejects.toThrow(NotOpened);
        await expect(openBox(box, "K7QF29MD", await newKeys(crypto), crypto)).rejects.toThrow(NotOpened);
        const changed = box.slice();
        changed[changed.length - 1] ^= 1;
        await expect(openBox(changed, "K7QF29MD", keys, crypto)).rejects.toThrow(NotOpened);
        await expect(openBox(new Uint8Array(10), "K7QF29MD", keys, crypto)).rejects.toThrow(NotOpened);
    });

    it("reads a gzipped sync copy", async () => {
        const payload = new Uint8Array([
            1,
            ...gzipSync(Buffer.from(JSON.stringify({ playlists: [{ playlistUrl: "http://x.example/list.m3u" }] }))),
        ]);
        expect(fromHandoff(await unpack(payload, gunzip))).toEqual([
            { kind: "m3u", url: "http://x.example/list.m3u", guideUrl: "", name: "" },
        ]);
    });
});
