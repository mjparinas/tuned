// One browser per account for the web player (docs/decisions/web.md): the web player doesn't take a device seat, but
// only one browser plays at a time. A short lease; a second browser takes over and the first stops. Plain functions so
// they are tested without Redis.

/** How long a lease lasts without a heartbeat (seconds). */
export const LEASE_SECONDS = 45;

/** A browser id the page made (UUID-shaped), or null. */
export function validBrowserId(id) {
    return typeof id === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id)
        ? id.toLowerCase()
        : null;
}

/**
 * Who holds the account's lease after this heartbeat: `{ holder, tookOver }` when this browser keeps or takes it, or
 * `{ holder, blocked: true }` when another browser still holds it and `steal` is false. `current` is the Redis value
 * (the holder's browser id), or null.
 */
export function claimLease(current, browserId, { steal = true } = {}) {
    const id = validBrowserId(browserId);
    if (!id) return { error: "bad request" };
    if (!current || current === id) return { holder: id, tookOver: false };
    if (!steal) return { holder: current, blocked: true };
    return { holder: id, tookOver: true };
}

/** True when this browser still holds the lease (`current` is the Redis value). */
export function holdsLease(current, browserId) {
    const id = validBrowserId(browserId);
    return Boolean(id && current === id);
}
