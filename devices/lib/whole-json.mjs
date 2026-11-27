// Backups hold the app's 64-bit ids (item ids are StableId's FNV-1a hashes, own lists' ids are random Longs), and most of
// them are past 2^53, where a JavaScript Number rounds. JSON.parse would turn -7046029254386353131 into
// -7046029254386353000 and every website edit would write the rounded ids back, so a device then joined ids that match
// nothing and lost favourites, lists and renamed channels (docs/decisions/accounts.md, "Big ids stay whole").
// So a backup is read with every integer a Number can't hold as a BigInt, and written with those BigInts as the same digits.

/** The JSON text with each integer too big for a Number read as a BigInt, every other value as JSON.parse reads it. */
export function parseWhole(text) {
    return JSON.parse(text, (_key, value, context) => {
        if (typeof value !== "number" || Number.isSafeInteger(value)) return value;
        const source = context?.source;
        return typeof source === "string" && /^-?\d+$/.test(source) ? BigInt(source) : value;
    });
}

/** JSON text for a value that may hold BigInts, each written as its digits, unquoted. */
export function stringifyWhole(value) {
    return JSON.stringify(value, (_key, v) => (typeof v === "bigint" ? JSON.rawJSON(v.toString()) : v));
}

const LONG_MIN = -(2n ** 63n);
const LONG_MAX = 2n ** 63n - 1n;

/**
 * An app id (a Kotlin Long, never 0) from a backup or a browser: a Number when it fits one, otherwise a BigInt, so it
 * is written back with the same digits. A browser sends big ids as strings. Null when it isn't one.
 */
export function wholeId(v) {
    let big;
    if (typeof v === "bigint") big = v;
    else if (typeof v === "number") {
        if (!Number.isSafeInteger(v)) return null;
        big = BigInt(v);
    } else if (typeof v === "string" && /^-?\d{1,19}$/.test(v.trim())) big = BigInt(v.trim());
    else return null;
    if (big === 0n || big < LONG_MIN || big > LONG_MAX) return null;
    return big >= BigInt(Number.MIN_SAFE_INTEGER) && big <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(big) : big;
}

/** The id as the website's pages see it: always its digits as a string, so a browser never rounds it. Null when it isn't one. */
export function idText(v) {
    const id = wholeId(v);
    return id == null ? null : String(id);
}

/** Whether two ids are the same, whichever of Number, BigInt or string each is. */
export const sameId = (a, b) => idText(a) != null && idText(a) === idText(b);

/** Orders by id as the app's sorted lists do, comparing whole values. */
export function byId(a, b) {
    const x = BigInt(wholeId(a) ?? 0);
    const y = BigInt(wholeId(b) ?? 0);
    return x < y ? -1 : x > y ? 1 : 0;
}
