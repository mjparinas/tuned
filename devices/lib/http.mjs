// Shared by the app's two endpoints: read and check the request, check the purchase, answer in one shape.
import { list, parse } from "./seats.mjs";
import { PRODUCTS } from "./play.mjs";
import { load, save, sha, valid } from "./store.mjs";

const json = (body, status = 200) => Response.json(body, { status, headers: { "Cache-Control": "no-store" } });

/**
 * Runs `decide(seats, ask, now)` for a checked request and saves what it returns. Any failure on our side or
 * Google's answers 503, which the app takes as "try later" and never as a lock.
 */
export async function seatRequest(request, decide) {
    let ask;
    try {
        ask = parse(await request.json(), PRODUCTS);
    } catch {
        ask = null;
    }
    if (!ask) return json({ status: "bad request" }, 400);
    try {
        const purchase = sha(ask.token);
        if (!(await valid(purchase, ask.product, ask.token))) return json({ status: "not purchased" }, 403);
        const outcome = decide(await load(purchase), ask, Date.now());
        if (outcome.allowed === false) return json({ status: "denied" }, 403);
        await save(purchase, outcome.seats);
        return json({ status: outcome.status, devices: list(outcome.seats) });
    } catch (error) {
        console.error(error);
        return json({ status: "unavailable" }, 503);
    }
}
