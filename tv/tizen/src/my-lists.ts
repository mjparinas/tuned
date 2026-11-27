// Favourites and your own lists, changed from the TV (lists.md). Signed in to an account with profiles, a change goes
// into the account's backup at once, as the website's does, and the phones join it on their next sync; a show's
// favourite, and every favourite on a TV with no account, stays on the TV. One change at a time, in the order made,
// so each is saved on top of the one before. The screen changes first and goes back, with a note, if the change
// didn't stick.
import type { Item } from "@tuned/shared";
import { changeBackup, type ListChange, type ListProblem } from "@tuned/shared/lists";
import { accountProfile, favourites, keepFavourite, takeBackup, tunedAccount } from "./app.ts";
import { log, safely } from "./log.ts";
import { store } from "./state.ts";
import { t } from "./strings.ts";

/** A line the screen shows for a few seconds when a change to the account didn't stick. */
export const notice = store<string | null>(null);

let queue: Promise<unknown> = Promise.resolve();

/** Makes `change` in the account's backup, after any change still saving. Null when it stuck. */
export function changeAccount(change: ListChange): Promise<ListProblem | null> {
    const run = queue.then(() =>
        safely(
            "Lists",
            async () => {
                const done = await changeBackup(tunedAccount, change, {
                    random: (b) => crypto.getRandomValues(b as Uint8Array<ArrayBuffer>),
                });
                if ("problem" in done) {
                    log("Lists", `A change to the account didn't stick: ${done.problem}`);
                    return done.problem;
                }
                await takeBackup(done.text);
                return null;
            },
            "offline" as ListProblem,
        ),
    );
    queue = run;
    return run;
}

/** Stars or unstars `item`: in the account when Favourites are the account's, otherwise on the TV. */
export async function toggleFavourite(item: Item): Promise<void> {
    const was = favourites.get();
    const on = !was.has(item.id);
    const next = new Set(was);
    if (on) next.add(item.id);
    else next.delete(item.id);
    favourites.set(next);
    const profile = accountProfile();
    if (!profile || item.kind === "series") return void (await safely("Favourites", () => keepFavourite(item.id, on), undefined));
    const failed = await changeAccount({ op: "favourite", profileId: profile.id, itemId: item.androidId, on });
    if (!failed) return;
    // Put it back as it was, and say why.
    const back = new Set(favourites.get());
    if (on) back.delete(item.id);
    else back.add(item.id);
    favourites.set(back);
    notice.set(t.listProblem[failed]);
}
