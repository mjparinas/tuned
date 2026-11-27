// What a playlist holds once it's read: channels, movies and shows as plain objects a TV app can keep (IndexedDB on
// Tizen), built from tv/core's entries so sorting, regions and links match Android's.
import type { Entry } from "./core.ts";

export type Kind = "live" | "movie" | "series";

export interface Item {
    /** Stable across refreshes: the section and a hash of the link, so favourites and positions survive. */
    readonly id: string;
    readonly kind: Kind;
    readonly name: string;
    readonly url: string;
    readonly group: string;
    readonly tvgId: string | null;
    readonly logo: string | null;
    readonly region: string | null;
    readonly adult: boolean;
    readonly showName: string | null;
    readonly season: number;
    readonly episode: number;
    readonly order: number;
    readonly added: number;
    readonly year: number | null;
    readonly rating: number | null;
    readonly catchUpDays: number;
    /** How the channel's recordings are reached (`guide.ts` `decodeCatchUp`): "xc" for an Xtream server, or a playlist's own. */
    readonly catchUp: string | null;
    /** Live channels are numbered from 1 in playlist order, as on Android (Settings › Channel numbers). */
    readonly number: number;
}

/** FNV-1a, 52 bits: short, fast and stable, so an id never carries the link (and its login) itself. */
export function hashText(text: string): string {
    let h1 = 0x811c9dc5;
    let h2 = 0x01000193;
    for (let i = 0; i < text.length; i++) {
        const c = text.charCodeAt(i);
        h1 = Math.imul(h1 ^ c, 16777619) >>> 0;
        h2 = Math.imul(h2 ^ c, 2246822519) >>> 0;
    }
    return (h1.toString(36) + h2.toString(36)).slice(0, 12);
}

export function toItem(entry: Entry, number = 0, extra: Partial<Pick<Item, "catchUpDays" | "catchUp">> = {}): Item {
    const kind: Kind = entry.kind === "movie" || entry.kind === "series" ? entry.kind : "live";
    return {
        id: `${kind[0]}${hashText(entry.url)}`,
        kind,
        name: entry.name,
        url: entry.url,
        group: entry.group,
        tvgId: entry.tvgId ?? null,
        logo: entry.logo ?? null,
        region: entry.region ?? null,
        adult: entry.adult,
        showName: entry.showName ?? null,
        season: entry.season,
        episode: entry.episode,
        order: entry.order,
        added: entry.added,
        year: entry.year ?? null,
        rating: entry.rating ?? null,
        catchUpDays: extra.catchUpDays ?? entry.catchUpDays,
        catchUp: extra.catchUp !== undefined ? extra.catchUp : (entry.catchUp ?? null),
        number,
    };
}

/** Numbers live channels 1 up as they arrive, across batches. */
export class ChannelNumbers {
    private next = 1;

    number(entry: Entry): number {
        return entry.kind === "live" ? this.next++ : 0;
    }
}
