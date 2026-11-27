// Android's id for a stream (`core/StableId`), for an app that reads its playlist without tv/core (Vega): the account's
// backup names favourites, lists and renamed channels by it, so a TV that works it out the same way finds them.
// It is FNV-1a 64 over "<kind code>|<link's last part, no extension>|<name>", as signed decimal text, the way the
// backup stores it. `@tuned/shared/stableid`.
import { utf8Encode } from "./bytes.ts";

const KIND = { live: 0, movie: 1, show: 2 } as const;

/**
 * FNV-1a 64 of `text`'s UTF-8 as 16 hex digits, in four 16-bit pieces of plain numbers: Hermes' BigInt is slow, and a
 * TV matching a whole playlist works out tens of thousands of these.
 */
export function fnv64Hex(text: string): string {
    // The offset basis, 0xcbf29ce484222325, lowest piece first.
    let h0 = 0x2325;
    let h1 = 0x8422;
    let h2 = 0x9ce4;
    let h3 = 0xcbf2;
    for (const byte of utf8Encode(text)) {
        h0 ^= byte;
        // × 0x100000001b3 is × 0x1b3 plus the hash moved up 40 bits, kept to 64 bits.
        let c0 = h0 * 0x1b3;
        let c1 = h1 * 0x1b3 + (c0 >>> 16);
        let c2 = h2 * 0x1b3 + Math.floor(c1 / 0x10000) + ((h0 << 8) & 0xffff);
        let c3 = h3 * 0x1b3 + Math.floor(c2 / 0x10000) + (((h0 >>> 8) | (h1 << 8)) & 0xffff);
        h0 = c0 & 0xffff;
        h1 = c1 % 0x10000;
        h2 = c2 % 0x10000;
        h3 = c3 % 0x10000;
    }
    return [h3, h2, h1, h0].map((x) => x.toString(16).padStart(4, "0")).join("");
}

/** FNV-1a 64 of `text`'s UTF-8, as Kotlin's signed Long would print it. */
export const fnv64 = (text: string): string => hexToId(fnv64Hex(text));

/** An id as the backup writes it (signed decimal) from its 16 hex digits, and back. */
export const hexToId = (hex: string): string => BigInt.asIntN(64, BigInt(`0x${hex}`)).toString();
export function idToHex(id: string): string | null {
    try {
        return BigInt.asUintN(64, BigInt(id)).toString(16).padStart(16, "0");
    } catch {
        return null;
    }
}

/** The link's last part without its extension, query or headers, as `StableId.of` takes it. */
export function linkTail(link: string): string {
    const bar = link.indexOf("|");
    const address = bar >= 0 && link.slice(bar).includes("=") ? link.slice(0, bar).trim() : link;
    const path = address.split("?")[0];
    const last = path.slice(path.lastIndexOf("/") + 1);
    const dot = last.lastIndexOf(".");
    return dot >= 0 ? last.slice(0, dot) : last;
}

/** Android's id for a channel, a movie, or an Xtream show (whose link is "xtream-series:<id>" there). */
export const androidId = (kind: keyof typeof KIND, link: string, name: string): string => hexToId(androidIdHex(kind, link, name));

/** `androidId` as 16 hex digits, the quick form for matching many against `idToHex` of the backup's. */
export const androidIdHex = (kind: keyof typeof KIND, link: string, name: string): string =>
    fnv64Hex(`${KIND[kind]}|${linkTail(link)}|${name}`);

/** The link Android files an Xtream show under until its episodes are read (`XtreamApi.STAND_IN`). */
export const xtreamShowLink = (seriesId: number | string): string => `xtream-series:${seriesId}`;
