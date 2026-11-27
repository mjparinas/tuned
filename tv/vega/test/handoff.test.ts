import { execFileSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import path from "node:path";
import { gzipSync } from "node:zlib";
import { b64, checkDigits, displayCode, link, loginFrom, newKeys, NotOpened, open, unb64, unpack } from "../src/core/handoff";

const random = (n: number) => new Uint8Array(randomBytes(n));
const sealWithPhonePage = (code: string, key: Uint8Array, json: string) =>
    unb64(execFileSync("node", [path.join(__dirname, "seal-fixture.mjs"), code, b64(key), json], { encoding: "utf8" }));

describe("handoff", () => {
    const keys = newKeys(random);
    const json = JSON.stringify({
        version: 1,
        playlists: [{ server: "http://tv.example:8080", username: "anna", password: "s3cret", name: "Home" }],
    });

    it("makes an uncompressed P-256 key the relay accepts", () => {
        expect(keys.publicKey.length).toBe(65);
        expect(keys.publicKey[0]).toBe(4);
        expect(b64(keys.publicKey)).toHaveLength(87);
    });

    it("opens a box sealed by the phone page", () => {
        const box = sealWithPhonePage("K7QF29MD", keys.publicKey, json);
        const login = loginFrom(unpack(open(box, "K7QF29MD", keys)));
        expect(login).toEqual({ kind: "xtream", server: "http://tv.example:8080", username: "anna", password: "s3cret", name: "Home" });
    });

    it("refuses a box for another code or another TV, or one changed on the way", () => {
        const box = sealWithPhonePage("K7QF29MD", keys.publicKey, json);
        expect(() => open(box, "AAAAAAAA", keys)).toThrow(NotOpened);
        expect(() => open(box, "K7QF29MD", newKeys(random))).toThrow(NotOpened);
        const changed = box.slice();
        changed[changed.length - 1] ^= 1;
        expect(() => open(changed, "K7QF29MD", keys)).toThrow(NotOpened);
        expect(() => open(new Uint8Array(10), "K7QF29MD", keys)).toThrow(NotOpened);
    });

    it("reads a gzipped sync copy and a playlist link", () => {
        const zipped = gzipSync(
            JSON.stringify({ playlists: [{ playlistUrl: "http://x.example/list.m3u", guideUrl: "http://x.example/epg.xml" }] }),
        );
        const payload = new Uint8Array(zipped.length + 1);
        payload[0] = 1;
        payload.set(zipped, 1);
        expect(loginFrom(unpack(payload))).toEqual({
            kind: "link",
            playlistUrl: "http://x.example/list.m3u",
            guideUrl: "http://x.example/epg.xml",
            name: undefined,
        });
        expect(loginFrom({ playlists: [] })).toBeNull();
    });

    it("shows the code and digits as Android does", () => {
        expect(displayCode("K7QF29MD")).toBe("K7QF-29MD");
        expect(checkDigits(keys.publicKey)).toMatch(/^\d{4}$/);
        // SHA-256 of 65 zero bytes starts 0x98ce42de; 0x98ce42de % 10000 = 6878.
        expect(checkDigits(new Uint8Array(65))).toBe(String(0x98ce42de % 10000).padStart(4, "0"));
        expect(link("https://www.tunedplayer.com/", "K7QF29MD", keys.publicKey)).toBe(
            `https://www.tunedplayer.com/tv#K7QF29MD.${b64(keys.publicKey)}`,
        );
    });
});
