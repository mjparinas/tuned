// useStore never misses a change made after a screen drew but before its effects ran (the spinner that stayed on
// screen after `ready` was set, seen as the Samsung e2e timing out on the welcome code now and then).
import assert from "node:assert/strict";
import { test } from "node:test";
import { h, options, render } from "preact";
import { store, useStore } from "./state.ts";

/** Just enough of a DOM for Preact to draw text into. */
class Node {
    childNodes: Node[] = [];
    parentNode: Node | null = null;
    data = "";
    style: Record<string, string> = {};
    readonly nodeType: number;
    readonly localName: string;
    constructor(nodeType: number, localName = "") {
        this.nodeType = nodeType;
        this.localName = localName;
    }
    get firstChild() {
        return this.childNodes[0] ?? null;
    }
    get nextSibling(): Node | null {
        const siblings = this.parentNode?.childNodes ?? [];
        return siblings[siblings.indexOf(this) + 1] ?? null;
    }
    get textContent(): string {
        return this.nodeType === 3 ? this.data : this.childNodes.map((c) => c.textContent).join("");
    }
    insertBefore(node: Node, before: Node | null) {
        node.parentNode?.removeChild(node);
        const at = before ? this.childNodes.indexOf(before) : -1;
        if (at < 0) this.childNodes.push(node);
        else this.childNodes.splice(at, 0, node);
        node.parentNode = this;
        return node;
    }
    appendChild(node: Node) {
        return this.insertBefore(node, null);
    }
    removeChild(node: Node) {
        this.childNodes = this.childNodes.filter((c) => c !== node);
        node.parentNode = null;
        return node;
    }
    remove() {
        this.parentNode?.removeChild(this);
    }
    setAttribute() {}
    removeAttribute() {}
    addEventListener() {}
    removeEventListener() {}
}

test("a change between the draw and the subscription still redraws", () => {
    const g = globalThis as Record<string, unknown>;
    g.document = {
        createElement: (name: string) => new Node(1, name),
        createElementNS: (_: string, name: string) => new Node(1, name),
        createTextNode: (text: string) => Object.assign(new Node(3), { data: text }),
    };
    const afterPaint: (() => void)[] = [];
    const redraws: (() => void)[] = [];
    options.requestAnimationFrame = (cb) => void afterPaint.push(cb);
    options.debounceRendering = (cb) => void redraws.push(cb);
    const flush = (list: (() => void)[]) => list.splice(0).forEach((cb) => cb());

    const ready = store(false);
    const root = new Node(1, "div");
    render(
        h(() => h("p", null, useStore(ready) ? "ready" : "loading"), null),
        root as never,
    );
    assert.equal(root.textContent, "loading");
    // The app finishes starting before the screen's effects run.
    ready.set(true);
    for (let i = 0; i < 3; i++) {
        flush(afterPaint);
        flush(redraws);
    }
    assert.equal(root.textContent, "ready");
});
