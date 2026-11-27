// Your own lists, Favourites and renamed channels on the TV apps (docs/decisions/lists.md), kept where the phone keeps
// them: the Tuned account's backup. The TV reads a profile's lists and the channels renamed or given a new logo on the
// phone or website, and changes Favourites and lists by editing the backup as the website does (`applyChange` in
// devices/lib/library.mjs): read it with its version, change it, save it on top of that version, and do it again on
// the newer copy when another device saved first. The phones join the change with their own on their next sync. As on
// Android TV, the TV never renames a channel, changes a logo or renames a list; that stays on the phone or website.
// Android's ids are 64-bit and are kept whole all the way through, never turned into a JavaScript number.
// `@tuned/shared/lists`.
/**
 * What these rules need of an item: catalog's `Item` fits, and so does Vega's own, without pulling in tv/core.
 * `androidId` is Android's id for the stream (`StableId`), as decimal text.
 */
export interface ListItem {
    readonly androidId: string;
    readonly kind: string;
    readonly name: string;
    readonly logo: string | null;
}

/** Marks an integer kept as its digits while the backup is open, so it's written back as the same number. */
const WHOLE = "\u0001";

/** The backup as JSON, with every integer of 16 digits or more outside a string kept as `WHOLE` + its digits. */
export function parseKeepingIds(text: string): unknown {
    let out = "";
    let from = 0;
    let inString = false;
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (inString) {
            if (c === "\\") i++;
            else if (c === '"') inString = false;
            continue;
        }
        if (c === '"') {
            inString = true;
            continue;
        }
        if (c === "-" || (c >= "0" && c <= "9")) {
            let end = i + 1;
            while (end < text.length && text[end] >= "0" && text[end] <= "9") end++;
            const next = text[end];
            const digits = end - i - (c === "-" ? 1 : 0);
            if (digits >= 16 && next !== "." && next !== "e" && next !== "E") {
                out += `${text.slice(from, i)}"\\u0001${text.slice(i, end)}"`;
                from = end;
            }
            i = end - 1;
        }
    }
    return JSON.parse(out + text.slice(from));
}

/** JSON text of a backup opened with `parseKeepingIds`, its long integers written back as numbers. */
export function stringifyKeepingIds(value: unknown): string {
    return JSON.stringify(value).replace(/"\\u0001(-?\d+)"/g, "$1");
}

/** An id as decimal text, whether it was a number, a kept long one, or text. */
export function idText(v: unknown): string | null {
    if (typeof v === "number") return Number.isSafeInteger(v) ? String(v) : null;
    if (typeof v !== "string") return null;
    const digits = v.startsWith(WHOLE) ? v.slice(1) : v;
    return /^-?\d+$/.test(digits) ? digits : null;
}

/** How an id goes back into the backup: a number when it fits one exactly, else kept whole. */
export function idValue(id: string): number | string {
    const n = Number(id);
    return Number.isSafeInteger(n) && String(n) === id ? n : WHOLE + id;
}

export interface UserList {
    /** Android's id for the list, as decimal text. */
    readonly id: string;
    readonly name: string;
    /** Android ids (`Item.androidId`) of what's in it, in the order they were added. */
    readonly items: readonly string[];
}

/** A channel renamed or given another logo on the phone or website; null leaves that side as the playlist has it. */
export interface ChannelEdit {
    readonly name: string | null;
    readonly logo: string | null;
}

export interface AccountLists {
    /** Each profile's own lists by profile id, in the order the phone shows them. */
    readonly lists: Readonly<Record<string, readonly UserList[]>>;
    /** Renamed channels and changed logos, by Android id. They belong to the account, not a profile. */
    readonly edits: Readonly<Record<string, ChannelEdit>>;
}

export const NO_LISTS: AccountLists = { lists: {}, edits: {} };

type Row = Record<string, unknown>;
const rows = (v: unknown): Row[] => (Array.isArray(v) ? v.filter((x): x is Row => !!x && typeof x === "object") : []);
const num = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) ? v : 0);

/** The sync copy inside the backup (`{fingerprint, changedAt, copy}`), or the copy itself. */
function copyOf(whole: unknown): Row | null {
    if (!whole || typeof whole !== "object") return null;
    const w = whole as Row;
    return w.copy && typeof w.copy === "object" ? (w.copy as Row) : w;
}

function listsOf(value: unknown): UserList[] {
    const out: UserList[] = [];
    for (const row of rows(value).sort((a, b) => num(a.position) - num(b.position))) {
        const id = idText(row.id);
        if (!id || typeof row.name !== "string" || !row.name.trim()) continue;
        const items = rows(row.items)
            .sort((a, b) => num(a.addedAt) - num(b.addedAt) || num(a.position) - num(b.position))
            .map((i) => idText(i.itemId))
            .filter((x): x is string => x !== null);
        out.push({ id, name: row.name, items });
    }
    return out;
}

/** A profile's lists and the account's channel edits from the backup's text; nothing when it can't be read. */
export function listsFromBackup(text: string | null): AccountLists {
    let copy: Row | null;
    try {
        copy = text ? copyOf(parseKeepingIds(text)) : null;
    } catch {
        copy = null;
    }
    if (!copy) return NO_LISTS;
    const lists: Record<string, UserList[]> = {};
    for (const p of rows(copy.profiles)) {
        const id = idText(p.id);
        if (id) lists[id] = listsOf(p.lists);
    }
    const edits: Record<string, ChannelEdit> = {};
    for (const e of rows(copy.channelEdits)) {
        const id = idText(e.itemId);
        const name = typeof e.name === "string" && e.name.trim() ? e.name : null;
        const logo = typeof e.logo === "string" && /^https?:\/\//i.test(e.logo) ? e.logo : null;
        if (id && (name || logo)) edits[id] = { name, logo };
    }
    return { lists, edits };
}

/** A list's items on this TV, matched by Android's id: channels in the order they were added, the rest in `items`' order. */
export function listOnTv<T extends Pick<ListItem, "androidId" | "kind">>(list: UserList, items: readonly T[]): T[] {
    const wanted = new Set(list.items);
    const found = items.filter((i) => wanted.has(i.androidId));
    if (!found.length || found[0].kind !== "live") return found;
    const order = new Map(list.items.map((id, at) => [id, at]));
    return found.sort((a, b) => (order.get(a.androidId) ?? 0) - (order.get(b.androidId) ?? 0));
}

/** An item as the account has it: a renamed channel's name and a changed logo. */
export function withEdit<T extends Pick<ListItem, "androidId" | "name" | "logo">>(item: T, edits: AccountLists["edits"]): T {
    const edit = edits[item.androidId];
    if (!edit) return item;
    return { ...item, name: edit.name ?? item.name, logo: edit.logo ?? item.logo };
}

/** A change the TV makes to the account's backup. Ids are Android's, as decimal text. */
export type ListChange =
    | { readonly op: "favourite"; readonly profileId: string; readonly itemId: string; readonly on: boolean }
    /** A new list, with `itemId` already inside when given ("Add to list" › New list). */
    | { readonly op: "list_add"; readonly profileId: string; readonly name: string; readonly itemId?: string }
    | { readonly op: "list_delete"; readonly profileId: string; readonly listId: string }
    | { readonly op: "list_item"; readonly profileId: string; readonly listId: string; readonly itemId: string; readonly on: boolean };

/** Why a change didn't stick, for the screen to say. */
export type ListProblem = "offline" | "gone" | "too many lists" | "bad name" | "busy";

/** The most lists a profile keeps, and the longest name, as the website allows. */
export const MAX_LISTS = 50;
export const MAX_LIST_NAME = 60;

const nextPosition = (list: Row[]) => list.reduce((m, r) => Math.max(m, num(r.position)), 0) + 1;

/** Makes `change` on an opened copy, as the website's `applyChange` does. `newId` gives a new list its id. */
export function applyListChange(copy: Row, change: ListChange, now: number, newId: () => string): ListProblem | null {
    const profile = rows(copy.profiles).find((p) => idText(p.id) === change.profileId);
    if (!profile) return "gone";
    const keyed = (row: Row, key: string) => {
        if (!Array.isArray(row[key])) row[key] = [];
        return row[key] as Row[];
    };
    const toggle = (row: Row, key: string, itemId: string, on: boolean) => {
        const rest = keyed(row, key).filter((r) => idText(r.itemId) !== itemId);
        row[key] = on ? [...rest, { itemId: idValue(itemId), addedAt: now, position: nextPosition(rest) }] : rest;
    };
    switch (change.op) {
        case "favourite":
            toggle(profile, "favourites", change.itemId, change.on);
            return null;
        case "list_add": {
            const name = change.name.trim().slice(0, MAX_LIST_NAME);
            if (!name) return "bad name";
            const lists = keyed(profile, "lists");
            if (lists.length >= MAX_LISTS) return "too many lists";
            const items = change.itemId ? [{ itemId: idValue(change.itemId), addedAt: now, position: 1 }] : [];
            profile.lists = [...lists, { id: idValue(newId()), name, position: nextPosition(lists), createdAt: now, items }];
            return null;
        }
        case "list_delete": {
            const lists = keyed(profile, "lists");
            const rest = lists.filter((l) => idText(l.id) !== change.listId);
            if (rest.length === lists.length) return "gone";
            profile.lists = rest;
            return null;
        }
        case "list_item": {
            const list = keyed(profile, "lists").find((l) => idText(l.id) === change.listId);
            if (!list) return "gone";
            toggle(list, "items", change.itemId, change.on);
            return null;
        }
    }
}

/** What `changeBackup` needs from the account (`TunedAccount` has both). */
export interface BackupAccess {
    backupToChange(): Promise<{ text: string; version: number } | "none" | null>;
    saveBackup(text: string, version: number): Promise<"ok" | "conflict" | "failed">;
}

/** A new list's id: random, positive, and small enough for any device to read exactly, as the website makes them. */
export function randomListId(random: (bytes: Uint8Array) => Uint8Array): string {
    const b = random(new Uint8Array(7));
    let n = 0;
    for (const x of b) n = n * 256 + x;
    return String((n % (Number.MAX_SAFE_INTEGER - 1)) + 1);
}

/**
 * Makes `change` in the account's backup and saves it, trying again on the newer copy when another device saved first.
 * Returns the backup's new text, to read the profiles and lists from, or why it didn't stick.
 */
export async function changeBackup(
    account: BackupAccess,
    change: ListChange,
    deps: { now?: () => number; random: (bytes: Uint8Array) => Uint8Array },
): Promise<{ text: string } | { problem: ListProblem }> {
    const now = deps.now ?? Date.now;
    for (let attempt = 0; attempt < 3; attempt++) {
        const found = await account.backupToChange();
        if (found === null) return { problem: "offline" };
        if (found === "none") return { problem: "gone" };
        let whole: Row;
        try {
            const parsed = parseKeepingIds(found.text);
            if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return { problem: "gone" };
            whole = parsed as Row;
        } catch {
            return { problem: "gone" };
        }
        // Android keeps `{fingerprint, changedAt, copy}`; a copy on its own is wrapped so the phones read it.
        const copy = whole.copy && typeof whole.copy === "object" ? (whole.copy as Row) : whole;
        const problem = applyListChange(copy, change, now(), () => randomListId(deps.random));
        if (problem) return { problem };
        const hex = Array.from(deps.random(new Uint8Array(12)), (x) => x.toString(16).padStart(2, "0")).join("");
        const text = stringifyKeepingIds({ ...(copy === whole ? {} : whole), fingerprint: `tv-${hex}`, changedAt: now(), copy });
        const saved = await account.saveBackup(text, found.version);
        if (saved === "ok") return { text };
        if (saved === "failed") return { problem: "offline" };
    }
    return { problem: "busy" };
}
