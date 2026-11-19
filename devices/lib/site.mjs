// The website's side (docs/decisions/premium.md): the order number from Google Play's receipt email finds the
// purchase, for someone who has lost every device. Lookups are limited per visitor, and removals per purchase.
import { createHash } from "node:crypto";
import { command } from "./redis.mjs";
import { orderKey } from "./play.mjs";
import { list, removeFromSite, SITE_DAYS } from "./seats.mjs";
import { load, orderKeyFor, save } from "./store.mjs";

const LOOKUPS_PER_HOUR = 10;
const json = (body, status = 200) => Response.json(body, { status, headers: { "Cache-Control": "no-store" } });

/** A visitor's lookups this hour; the address is kept only hashed, and only for the hour. */
async function overLimit(request) {
    const ip = (request.headers.get("x-forwarded-for") ?? "").split(",")[0].trim();
    const key = `lookups:${createHash("sha256").update(ip).digest("hex")}`;
    const count = await command("INCR", key);
    if (count === 1) await command("EXPIRE", key, 3600);
    return count > LOOKUPS_PER_HOUR;
}

const looksLikeOrder = (order) => /^GPA\.\d{4}-\d{4}-\d{4}-\d{5}$/.test(order);

/** Handles `{ order }` (list) and `{ order, remove }` (remove one device id, or "all"). */
export async function siteRequest(request, removing) {
    let body;
    try {
        body = await request.json();
    } catch {
        body = null;
    }
    const order = orderKey(body?.order);
    if (!looksLikeOrder(order)) return json({ status: "bad order" }, 400);
    try {
        if (await overLimit(request)) return json({ status: "too many" }, 429);
        const purchase = await command("GET", orderKeyFor(order));
        if (!purchase) return json({ status: "not found" }, 404);
        const seats = await load(purchase);
        const waitKey = `site:${purchase}`;
        if (!removing) {
            const wait = await command("TTL", waitKey);
            return json({ status: "ok", devices: list(seats), nextRemovalDays: wait > 0 ? Math.ceil(wait / 86400) : 0 });
        }
        const target = body?.remove;
        if (target !== "all" && !(typeof target === "string" && seats[target])) return json({ status: "bad device" }, 400);
        const allowed = await command("SET", waitKey, "1", "EX", SITE_DAYS * 86400, "NX");
        if (allowed !== "OK") return json({ status: "wait" }, 429);
        const left = removeFromSite(seats, target, Date.now());
        await save(purchase, left);
        return json({ status: "ok", devices: list(left), nextRemovalDays: SITE_DAYS });
    } catch (error) {
        console.error(error);
        return json({ status: "unavailable" }, 503);
    }
}
