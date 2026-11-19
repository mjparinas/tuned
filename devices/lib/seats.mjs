// The rules of the Premium device list (docs/decisions/premium.md), kept free of storage and HTTP so they can be
// tested. Melvin, 2026-11-19: "like a phone, a tv, and maybe one more. beyond that, it should stop them".

/** Devices one purchase can use Premium on at once. */
export const LIMIT = 3;

/** A device not seen for this long gives its seat back on its own, so a lost or broken phone never holds one. */
export const EXPIRY_DAYS = 60;

const DAY = 24 * 60 * 60 * 1000;

/** The seats still held at `now`: `seats` maps a device id to `{ name, lastSeen }`. */
export function fresh(seats, now) {
    return Object.fromEntries(Object.entries(seats).filter(([, seat]) => now - seat.lastSeen < EXPIRY_DAYS * DAY));
}

/** A device asking for a seat: it keeps its own, takes a free one, or hears "full". */
export function activate(seats, device, name, now) {
    const live = fresh(seats, now);
    if (live[device] || Object.keys(live).length < LIMIT) {
        live[device] = { name, lastSeen: now };
        return { status: "active", seats: live };
    }
    return { status: "full", seats: live };
}

/**
 * `caller` freeing `target`'s seat. A device can always give up its own; only a device holding a seat can free
 * another's, so a locked-out device can't push its way in.
 */
export function deactivate(seats, caller, target, now) {
    const live = fresh(seats, now);
    if (target !== caller && !live[caller]) return { allowed: false, status: "full", seats: live };
    delete live[target];
    if (live[caller]) live[caller] = { ...live[caller], lastSeen: now };
    return { allowed: true, status: live[caller] ? "active" : "full", seats: live };
}

/** The seats as the app shows them, most recently used first. */
export function list(seats) {
    return Object.entries(seats)
        .map(([id, seat]) => ({ id, name: seat.name, lastSeen: seat.lastSeen }))
        .sort((a, b) => b.lastSeen - a.lastSeen);
}

/** The request body, checked; null when anything is missing or malformed. */
export function parse(body, products) {
    if (!body || typeof body !== "object") return null;
    const { product, token, device, name, target } = body;
    if (!products.includes(product)) return null;
    if (typeof token !== "string" || token.length < 10 || token.length > 2000) return null;
    if (typeof device !== "string" || !/^[0-9a-f]{32}$/.test(device)) return null;
    if (target !== undefined && target !== null && (typeof target !== "string" || !/^[0-9a-f]{32}$/.test(target))) return null;
    const label = typeof name === "string" && name.trim() ? name.trim().slice(0, 60) : "Android device";
    return { product, token, device, name: label, target: target ?? null };
}

/** Days between removals on the website, so an order number passed around can't keep making room. */
export const SITE_DAYS = 30;

/** The website removing one device (`target`) or every device (`"all"`). */
export function removeFromSite(seats, target, now) {
    const live = fresh(seats, now);
    if (target === "all") return {};
    delete live[target];
    return live;
}
