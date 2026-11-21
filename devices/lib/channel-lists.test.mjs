// The channel lists the app shares (lib/channel-lists.mjs, docs/decisions/web.md › The app's copy).
import { test } from "node:test";
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import { MAX_GZIPPED_BYTES, readUpload } from "./channel-lists.mjs";

const gz = (value) => gzipSync(typeof value === "string" ? value : JSON.stringify(value));
const good = {
    v: 1,
    playlist: "74d5b50ccf496f21",
    savedAt: 1,
    channels: [["CBC", "Canada", "", "cbc.ca", 1, "http://p/live/{login}/1.ts"]],
    guide: { "cbc.ca": [[1, 2, "News"]] },
};

test("a channel list from the app is read with its playlist and size", () => {
    assert.deepEqual(readUpload(gz(good)), { playlist: "74d5b50ccf496f21", channels: 1 });
    assert.deepEqual(readUpload(gz({ ...good, channels: [], guide: {} })), { playlist: "74d5b50ccf496f21", channels: 0 });
});

test("a newer app's rows, with country and adult, are read too", () => {
    const newer = { ...good, channels: [["CBC", "Canada", "", "cbc.ca", 1, "http://p/live/{login}/1.ts", "CA", 0]] };
    assert.deepEqual(readUpload(gz(newer)), { playlist: good.playlist, channels: 1 });
});

test("anything else is turned away", () => {
    assert.equal(readUpload(Buffer.alloc(0)).error, "bad request");
    assert.equal(readUpload(Buffer.from("not gzip")).error, "bad request");
    assert.equal(readUpload(gz("not json")).error, "bad request");
    assert.equal(readUpload(gz({ ...good, v: 2 })).error, "bad request");
    assert.equal(readUpload(gz({ ...good, playlist: "../../x" })).error, "bad request");
    assert.equal(readUpload(gz({ ...good, guide: [] })).error, "bad request");
    assert.equal(readUpload(gz({ ...good, channels: [["CBC"]] })).error, "bad request");
    assert.equal(readUpload(gz({ ...good, channels: [[1, "", "", "", 1, "x"]] })).error, "bad request");
    assert.equal(readUpload(Buffer.alloc(MAX_GZIPPED_BYTES + 1)).error, "too large");
    // A small upload that unpacks into something huge stops at the limit.
    assert.equal(readUpload(gzipSync(Buffer.alloc(65 * 1024 * 1024, 32))).error, "too large");
});
