import assert from "node:assert/strict";
import { test } from "node:test";
import { isTyping, readKey } from "./keys.ts";

test("Samsung's remote codes and a keyboard's map to the same actions", () => {
    assert.equal(readKey({ keyCode: 10009 }).action, "back");
    assert.equal(readKey({ keyCode: 27 }).action, "back");
    assert.equal(readKey({ keyCode: 427 }).action, "channelUp");
    assert.equal(readKey({ keyCode: 33 }).action, "channelUp");
    assert.equal(readKey({ keyCode: 428 }).action, "channelDown");
    assert.equal(readKey({ keyCode: 13 }).action, "ok");
    assert.equal(readKey({ keyCode: 10252 }).action, "playPause");
    assert.equal(readKey({ keyCode: 1234 }).action, null);
});

test("number keys are digits, on the remote and the keypad", () => {
    assert.deepEqual(readKey({ keyCode: 55 }), { action: null, digit: 7 });
    assert.deepEqual(readKey({ keyCode: 96 }), { action: null, digit: 0 });
});

test("Backspace is Back, except while typing, where it deletes", () => {
    assert.equal(readKey({ keyCode: 8, target: { tagName: "DIV" } }).action, "back");
    assert.equal(readKey({ keyCode: 8, target: { tagName: "INPUT" } }).action, null);
    assert.equal(isTyping({ tagName: "TEXTAREA" }), true);
    assert.equal(isTyping(null), false);
});
