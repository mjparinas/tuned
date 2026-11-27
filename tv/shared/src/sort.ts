// Sorting Movies and Shows as Android does (docs/decisions/playlist.md): Playlist order (the default), Recently added,
// Newest, Top rated and A to Z. Each tab keeps its own choice. Items without the value sorted on go last, in playlist
// order, so a list never jumps about. `@tuned/shared/sort`.

export type SortOrder = "provider" | "added" | "year" | "rating" | "title";

export const SORT_ORDERS: readonly SortOrder[] = ["provider", "added", "year", "rating", "title"];

export interface Sortable {
    name: string;
    /** When the provider added it, in ms. */
    added?: number | null;
    year?: number | null;
    /** Out of 10, as the poster shows it. */
    rating?: number | null;
}

/** The next choice after `order`, for a TV's button that steps through them. */
export function nextSort(order: SortOrder): SortOrder {
    return SORT_ORDERS[(SORT_ORDERS.indexOf(order) + 1) % SORT_ORDERS.length];
}

export function isSortOrder(value: unknown): value is SortOrder {
    return typeof value === "string" && (SORT_ORDERS as readonly string[]).includes(value);
}

const collator = typeof Intl !== "undefined" && Intl.Collator ? new Intl.Collator(undefined, { sensitivity: "base", numeric: true }) : null;
const compareNames = (a: string, b: string) => (collator ? collator.compare(a, b) : a.toLowerCase() < b.toLowerCase() ? -1 : a > b ? 1 : 0);

/** "The Office" sorts under O, as on Android. */
const sortName = (name: string) => name.replace(/^(the|a|an)\s+/i, "").trim();

/**
 * A new list in `order`; `rating` can give a better rating than the item's own (TMDB's, when it's been read).
 * Descending for added, year and rating; ties keep playlist order.
 */
export function sortItems<T extends Sortable>(items: readonly T[], order: SortOrder, rating?: (item: T) => number | null | undefined): T[] {
    const indexed = items.map((item, index) => ({ item, index }));
    if (order === "provider") return items.slice();
    const value = (item: T): number | null => {
        switch (order) {
            case "added":
                return item.added ?? null;
            case "year":
                return item.year ?? null;
            case "rating":
                return (rating ? rating(item) : null) ?? item.rating ?? null;
            default:
                return null;
        }
    };
    if (order === "title") {
        indexed.sort((a, b) => compareNames(sortName(a.item.name), sortName(b.item.name)) || a.index - b.index);
    } else {
        const values = new Map(indexed.map((x) => [x.index, value(x.item)]));
        indexed.sort((a, b) => {
            const va = values.get(a.index) ?? null;
            const vb = values.get(b.index) ?? null;
            if (va === null || vb === null) return va === vb ? a.index - b.index : va === null ? 1 : -1;
            return vb - va || a.index - b.index;
        });
    }
    return indexed.map((x) => x.item);
}
