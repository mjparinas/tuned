// The logo relay's rules (lib/logo-relay.mjs, docs/decisions/web.md › Logos).
import { test } from "node:test";
import assert from "node:assert/strict";
import { fetchLogo, imageType, isPrivateAddress, logoUrl, MAX_LOGO_BYTES, publicHost } from "./logo-relay.mjs";

test("private, loopback and link-local addresses are refused; public ones aren't", () => {
    for (const a of [
        "10.0.0.1",
        "127.0.0.1",
        "192.168.1.5",
        "172.20.0.1",
        "169.254.169.254",
        "100.64.0.1",
        "0.0.0.0",
        "::1",
        "fd00::1",
        "fe80::1",
        "::ffff:10.0.0.1",
    ])
        assert.equal(isPrivateAddress(a), true, a);
    for (const a of ["8.8.8.8", "172.32.0.1", "2606:4700::1111", "::ffff:1.1.1.1"]) assert.equal(isPrivateAddress(a), false, a);
    assert.equal(isPrivateAddress("not an address"), true);
});

test("only http and https addresses with a host and no login are fetched", () => {
    assert.equal(logoUrl("http://img.example/a.png")?.hostname, "img.example");
    assert.equal(logoUrl("ftp://img.example/a.png"), null);
    assert.equal(logoUrl("http://me:pw@img.example/a.png"), null);
    assert.equal(logoUrl("javascript:alert(1)"), null);
    assert.equal(logoUrl(""), null);
});

test("a host is public only when every address it has is", async () => {
    const resolve = async (host) =>
        ({ ok: [{ address: "8.8.8.8" }], mixed: [{ address: "8.8.8.8" }, { address: "10.0.0.2" }] })[host] ?? [];
    assert.equal(await publicHost("ok", resolve), true);
    assert.equal(await publicHost("mixed", resolve), false);
    assert.equal(await publicHost("gone", resolve), false);
    assert.equal(await publicHost("localhost", resolve), false);
    assert.equal(await publicHost("127.0.0.1", resolve), false);
    assert.equal(await publicHost("[::1]", resolve), false);
});

test("pictures pass; SVG and anything else don't", () => {
    assert.equal(imageType("image/png"), "image/png");
    assert.equal(imageType("IMAGE/JPEG; charset=binary"), "image/jpeg");
    assert.equal(imageType("image/svg+xml"), null);
    assert.equal(imageType("text/html"), null);
    assert.equal(imageType(null), null);
});

const publicDns = async () => [{ address: "93.184.216.34" }];
const answer = (status, headers, body = new Uint8Array([1, 2, 3])) => new Response(status === 200 ? body : null, { status, headers });

test("a logo is fetched, following a redirect but never onto a private address", async () => {
    const got = await fetchLogo("http://img.example/a.png", {
        resolve: publicDns,
        fetcher: async () => answer(200, { "content-type": "image/png" }),
    });
    assert.equal(got.type, "image/png");
    assert.deepEqual([...got.bytes], [1, 2, 3]);

    const seen = [];
    const fetcher = async (url) => {
        seen.push(url.href);
        return url.hostname === "img.example"
            ? answer(302, { location: "http://cdn.example/b.png" })
            : answer(200, { "content-type": "image/webp" });
    };
    assert.equal((await fetchLogo("http://img.example/a.png", { resolve: publicDns, fetcher })).type, "image/webp");
    assert.deepEqual(seen, ["http://img.example/a.png", "http://cdn.example/b.png"]);

    const intoLan = async () => answer(302, { location: "http://192.168.0.1/admin" });
    assert.deepEqual(await fetchLogo("http://img.example/a.png", { resolve: publicDns, fetcher: intoLan }), { error: "refused" });
});

test("not a picture, too big, or not there: no logo", async () => {
    const html = async () => answer(200, { "content-type": "text/html" });
    assert.deepEqual(await fetchLogo("http://img.example/a.png", { resolve: publicDns, fetcher: html }), { error: "not an image" });
    const big = async () => answer(200, { "content-type": "image/png" }, new Uint8Array(MAX_LOGO_BYTES + 1));
    assert.deepEqual(await fetchLogo("http://img.example/a.png", { resolve: publicDns, fetcher: big }), { error: "too large" });
    const missing = async () => answer(404, {});
    assert.deepEqual(await fetchLogo("http://img.example/a.png", { resolve: publicDns, fetcher: missing }), { error: "unreachable" });
    const down = async () => {
        throw new Error("down");
    };
    assert.deepEqual(await fetchLogo("http://img.example/a.png", { resolve: publicDns, fetcher: down }), { error: "unreachable" });
    assert.deepEqual(await fetchLogo("file:///etc/passwd", { resolve: publicDns, fetcher: down }), { error: "bad request" });
});
