// Where the device list lives in Redis. Only hashes are stored: of the purchase token, of the device id (the app
// sends Android's id already hashed), and of the order number; plus the device's name and when it was last used.
import { createHash } from "node:crypto";
import { command, hash, transaction } from "./redis.mjs";
import { check } from "./play.mjs";

const KEEP_SECONDS = 400 * 24 * 60 * 60;
const CHECKED_SECONDS = 6 * 60 * 60;

export const sha = (text) => createHash("sha256").update(text).digest("hex");

const seatsKey = (purchase) => `seats:${purchase}`;
const checkedKey = (purchase) => `checked:${purchase}`;
export const orderKeyFor = (order) => `order:${sha(order)}`;

/** The seats of one purchase (its token's hash): device id to `{ name, lastSeen }`. */
export async function load(purchase) {
    const raw = await hash(seatsKey(purchase));
    return Object.fromEntries(Object.entries(raw).map(([device, json]) => [device, JSON.parse(json)]));
}

/** Replaces a purchase's seats. */
export async function save(purchase, seats) {
    const key = seatsKey(purchase);
    const fields = Object.entries(seats).flatMap(([device, seat]) => [device, JSON.stringify(seat)]);
    const commands = [["DEL", key]];
    if (fields.length) commands.push(["HSET", key, ...fields], ["EXPIRE", key, KEEP_SECONDS]);
    await transaction(commands);
}

/**
 * True when Play says the purchase is paid for. A good answer is remembered for six hours, so Play is asked about
 * each purchase a few times a day at most. The order number is linked to the purchase for the website.
 */
export async function valid(purchase, product, token) {
    if ((await command("GET", checkedKey(purchase))) === "1") return true;
    const { valid: ok, order } = await check(product, token);
    if (!ok) return false;
    const commands = [["SET", checkedKey(purchase), "1", "EX", CHECKED_SECONDS]];
    if (order) commands.push(["SET", orderKeyFor(order), purchase, "EX", KEEP_SECONDS]);
    await transaction(commands);
    return true;
}
