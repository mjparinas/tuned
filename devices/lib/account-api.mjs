// What the endpoints share: an account's state in one shape, and Play purchases kept fresh.
import { holdsSeat, PRODUCTS as PLAY_PRODUCTS, seatPlan, sha } from "./accounts.mjs";
import * as store from "./account-store.mjs";
import { checkEntitlement } from "./play.mjs";

const RECHECK_MS = 6 * 3_600_000;

/**
 * The Play purchases a device reports (`[{ product, token }]`, which the app sends with each seat request, as it
 * did before accounts) saved to the account, asking Play only about ones new or not checked for six hours. Only
 * the token's hash is kept, so the app's report is what lets the server ask Play again. Play being unreachable
 * keeps what was last heard, so nobody loses Premium to an outage.
 */
export async function reportPlay(accountId, purchases, known, now = Date.now()) {
    for (const { product, token } of purchases) {
        const ref = sha(token);
        const seen = known.find((e) => e.source === "play" && e.ref === ref);
        if (seen && now - seen.checkedAt < RECHECK_MS) continue;
        let found;
        try {
            found = await checkEntitlement(product, token);
        } catch (error) {
            console.error(error);
            continue;
        }
        if (found) await store.saveEntitlement({ source: "play", ref, account: accountId, product, ...found });
    }
}

/** The Play purchases in a request body: at most a few, each a known product and a token. */
export function parsePurchases(list) {
    if (!Array.isArray(list)) return [];
    return list
        .slice(0, 8)
        .filter((p) => PLAY_PRODUCTS.includes(p?.product) && typeof p?.token === "string" && p.token.length > 10 && p.token.length < 2048);
}

/**
 * The hashes of the reported Play purchases the account has recorded as active. The app consumes a one-time seat
 * purchase once it is recorded, so Play lets the person buy more seats later; the account keeps counting it.
 */
export function recordedPurchases(purchases, entitlements) {
    const active = new Set(entitlements.filter((e) => e.source === "play" && e.status === "active").map((e) => e.ref));
    return purchases.map((p) => sha(p.token)).filter((ref) => active.has(ref));
}

/** Everything the website and the app show about an account. */
export async function summary(accountId, thisDevice = null, now = Date.now()) {
    const [account, entitlements, devices, backups] = await Promise.all([
        store.account(accountId),
        store.entitlements(accountId),
        store.devices(accountId),
        store.backupInfo(accountId),
    ]);
    if (!account) return null;
    const plan = seatPlan(entitlements, now);
    return {
        status: "ok",
        email: account.email,
        name: account.name,
        premium: plan.premium,
        seats: { limit: plan.limit, used: devices.filter((d) => holdsSeat(d, now)).length, seatKind: plan.seatKind, canBuy: plan.canBuy },
        purchases: entitlements
            .filter((e) => e.status === "active" && (e.expiresAt == null || e.expiresAt > now))
            .map((e) => ({ source: e.source, product: e.product, quantity: e.quantity, renews: e.expiresAt })),
        devices: devices.map((d) => ({
            id: d.id,
            name: d.name,
            kind: d.kind,
            build: d.build,
            seated: holdsSeat(d, now),
            lastSeen: d.lastSeen,
            thisDevice: d.id === thisDevice,
        })),
        backup: backups[0]
            ? { ...backups[0], versions: backups.map((b) => ({ version: b.version, savedAt: b.savedAt, device: b.device })) }
            : null,
    };
}
