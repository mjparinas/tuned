// Moving with the remote's arrows (docs/decisions/tv.md › Where focus goes): the nearest focusable thing in that
// direction, inside the top layer only (a dialog or the player's controls keep the remote to themselves). Anything
// with `data-focus` can be landed on; the accent ring follows `:focus`. Lists render a few rows past what's on screen,
// so the arrow always has a next row to land on, and that row scrolls into view.

const SELECTOR = "[data-focus]";

function layer(): ParentNode {
    const layers = document.querySelectorAll<HTMLElement>("[data-layer]");
    return layers.length ? layers[layers.length - 1] : document;
}

function visible(el: HTMLElement): boolean {
    if (el.hasAttribute("disabled")) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
}

export function focusables(root: ParentNode = layer()): HTMLElement[] {
    return Array.from(root.querySelectorAll<HTMLElement>(SELECTOR)).filter(visible);
}

export type Direction = "up" | "down" | "left" | "right";

/**
 * The best candidate from `from` toward `direction`: one whose middle lies that way, scored by distance along the
 * direction plus twice the gap across it (none when they overlap, so a wide text box below a small tab still counts as
 * straight below), so a row straight below wins over a nearer one off to the side.
 */
export function nearest(from: HTMLElement, direction: Direction, candidates: HTMLElement[]): HTMLElement | null {
    const a = from.getBoundingClientRect();
    const ax = a.left + a.width / 2;
    const ay = a.top + a.height / 2;
    let best: HTMLElement | null = null;
    let bestScore = Infinity;
    // Up and down stay inside their region (Home's rail, or the section beside it): only left and right cross over.
    const region = direction === "up" || direction === "down" ? from.closest("[data-region]") : null;
    for (const el of candidates) {
        if (el === from || (region && !region.contains(el))) continue;
        const b = el.getBoundingClientRect();
        const bx = b.left + b.width / 2;
        const by = b.top + b.height / 2;
        let along: number;
        let across: number;
        switch (direction) {
            case "up":
                if (b.bottom > a.top + 1 && by >= ay) continue;
                along = a.top - b.bottom;
                across = gap(a.left, a.right, b.left, b.right) * 2 + Math.abs(bx - ax) / 10;
                break;
            case "down":
                if (b.top < a.bottom - 1 && by <= ay) continue;
                along = b.top - a.bottom;
                across = gap(a.left, a.right, b.left, b.right) * 2 + Math.abs(bx - ax) / 10;
                break;
            case "left":
                if (b.right > a.left + 1 && bx >= ax) continue;
                along = a.left - b.right;
                across = gap(a.top, a.bottom, b.top, b.bottom) * 2 + Math.abs(by - ay) / 10;
                break;
            default:
                if (b.left < a.right - 1 && bx <= ax) continue;
                along = b.left - a.right;
                across = gap(a.top, a.bottom, b.top, b.bottom) * 2 + Math.abs(by - ay) / 10;
        }
        const score = Math.max(0, along) + across;
        if (score < bestScore) {
            bestScore = score;
            best = el;
        }
    }
    // Coming into a row of buttons or tabs from above or below lands on its chosen tab, else its first item, the way
    // a person reads it, rather than whichever happens to sit nearest.
    const row = best?.closest("[data-row]");
    if (best && row && !row.contains(from) && (direction === "up" || direction === "down")) {
        const inRow = candidates.filter((el) => row.contains(el));
        return inRow.find((el) => el.getAttribute("aria-selected") === "true") ?? inRow[0] ?? best;
    }
    return best;
}

/** How far apart two spans are; 0 when they overlap. */
const gap = (a1: number, a2: number, b1: number, b2: number) => Math.max(0, b1 - a2, a1 - b2);

export function focus(el: HTMLElement | null | undefined): boolean {
    if (!el) return false;
    el.focus({ preventScroll: true });
    el.scrollIntoView?.({ block: "nearest", inline: "nearest" });
    return true;
}

/** Moves focus one step; false when there's nothing that way (the screen may then handle the key itself). */
export function move(direction: Direction): boolean {
    const all = focusables();
    const current = document.activeElement as HTMLElement | null;
    if (!current || !all.includes(current)) return focus(all[0]);
    return focus(nearest(current, direction, all));
}

/**
 * Whether the remote sits where the app put it rather than where the person moved it. A list that fills in after its
 * screen opened takes the remote then (the first channel, not the rail the screen fell back to while it loaded).
 */
let placedByApp = false;
export const remotePlacedByApp = (): boolean => placedByApp;
/** The person pressed something: from now on the remote is theirs until a screen opens. */
export function remoteMoved(): void {
    placedByApp = false;
}

/** Puts the remote on the likely choice when a screen opens: `data-start` first, else the first focusable. */
export function focusStart(root: ParentNode = layer()): void {
    const current = document.activeElement as HTMLElement | null;
    const all = focusables(root);
    if (current && all.includes(current)) return;
    if (focus(root.querySelector<HTMLElement>("[data-start]") ?? all[0])) placedByApp = true;
}

/**
 * Keeps the remote on something. When the focused element leaves the page (a screen swapped under it, a list redrawn),
 * the browser drops focus to the page itself and OK would do nothing useful; after each change to the page, a remote
 * left on nothing goes back to the top layer's likely choice.
 */
export function keepFocus(root: Node): void {
    let queued = false;
    const check = () => {
        queued = false;
        const current = document.activeElement;
        if (current && current !== document.body && current.isConnected) return;
        focusStart();
    };
    new MutationObserver(() => {
        if (queued) return;
        queued = true;
        requestAnimationFrame(check);
    }).observe(root, { childList: true, subtree: true });
}
