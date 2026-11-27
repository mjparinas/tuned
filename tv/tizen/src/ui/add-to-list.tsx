// "Add to list" (lists.md): ticks the lists a channel or a movie is in, or makes a new one with it already inside,
// typed with the TV's own keyboard. Blue on a channel opens it, as does the button on a movie's page. Your own lists
// are Premium and live in the Tuned account, so a free or signed-out TV says so instead. Each tick says it's saving
// until the account has it, and says why when it didn't stick.
import { useEffect, useRef, useState } from "preact/hooks";
import { focusStart } from "../focus.ts";
import type { Item } from "@tuned/shared";
import { MAX_LIST_NAME, type ListProblem } from "@tuned/shared/lists";
import { account, accountLists, accountProfile, myLists, profileId } from "../app.ts";
import { changeAccount } from "../my-lists.ts";
import { useStore } from "../state.ts";
import { t } from "../strings.ts";
import { useKeys } from "./nav.ts";
import { Button, Screen, Spinner } from "./parts.tsx";

export function AddToList(props: { item: Item; onClose: () => void }) {
    const acc = useStore(account);
    useStore(accountLists);
    useStore(profileId);
    const profile = accountProfile();
    const lists = myLists();
    const [busy, setBusy] = useState<string | null>(null);
    const [problem, setProblem] = useState<ListProblem | null>(null);
    const [naming, setNaming] = useState(false);
    const [name, setName] = useState("");

    // Opening or closing the name field puts the remote on the field, or back on the lists.
    const first = useRef(true);
    useEffect(() => {
        if (first.current) return void (first.current = false);
        const id = requestAnimationFrame(() => {
            const dialog = document.querySelector<HTMLElement>(".dialog.lists");
            if (dialog) focusStart(dialog);
        });
        return () => cancelAnimationFrame(id);
    }, [naming]);

    useKeys((press) => {
        if (press.action !== "back") return false;
        if (naming) setNaming(false);
        else props.onClose();
        return true;
    });

    const run = async (key: string, change: Parameters<typeof changeAccount>[0]) => {
        if (busy) return;
        setBusy(key);
        setProblem(null);
        const failed = await changeAccount(change);
        setBusy(null);
        setProblem(failed);
        return failed;
    };

    const why = !profile ? t.listsSignIn : !acc.premium ? t.listsPremium : null;
    return (
        <div class="dialog-backdrop">
            <Screen class="dialog lists" layer>
                <h2>{t.addToListTitle(props.item.name)}</h2>
                {why || !profile ? (
                    <>
                        <p class="muted">{why}</p>
                        <div class="buttons" data-row>
                            <Button start label={t.done} onClick={props.onClose} />
                        </div>
                    </>
                ) : naming ? (
                    <>
                        <input
                            class="field"
                            data-focus
                            data-start
                            type="text"
                            maxLength={MAX_LIST_NAME}
                            value={name}
                            placeholder={t.listName}
                            aria-label={t.listName}
                            onInput={(e) => setName((e.currentTarget as HTMLInputElement).value)}
                        />
                        <div class="buttons" data-row>
                            <Button
                                kind="primary"
                                label={t.create}
                                busy={busy === "new"}
                                disabled={!name.trim()}
                                onClick={() =>
                                    void run("new", { op: "list_add", profileId: profile.id, name, itemId: props.item.androidId }).then(
                                        (failed) => {
                                            if (failed) return;
                                            setNaming(false);
                                            setName("");
                                        },
                                    )
                                }
                            />
                            <Button label={t.cancel} onClick={() => setNaming(false)} />
                        </div>
                    </>
                ) : (
                    <>
                        <div class="list-choices">
                            {lists.map((l, i) => {
                                const inside = l.items.includes(props.item.androidId);
                                return (
                                    <button
                                        key={l.id}
                                        type="button"
                                        class="row toggle"
                                        data-focus
                                        data-row
                                        data-start={i === 0 ? "" : undefined}
                                        role="checkbox"
                                        aria-checked={inside}
                                        aria-busy={busy === l.id ? "true" : undefined}
                                        onClick={() =>
                                            void run(l.id, {
                                                op: "list_item",
                                                profileId: profile.id,
                                                listId: l.id,
                                                itemId: props.item.androidId,
                                                on: !inside,
                                            })
                                        }
                                    >
                                        <span class="grow">{l.name}</span>
                                        {busy === l.id ? (
                                            <Spinner small />
                                        ) : (
                                            <span class={inside ? "check on" : "check"}>{inside ? "✓" : ""}</span>
                                        )}
                                    </button>
                                );
                            })}
                        </div>
                        {!lists.length ? <p class="muted">{t.noListsYet}</p> : null}
                        <div class="buttons" data-row>
                            <Button start={!lists.length} label={t.newList} onClick={() => setNaming(true)} />
                            <Button label={t.done} onClick={props.onClose} />
                        </div>
                    </>
                )}
                <p class={problem ? "error" : "muted small"} aria-live="polite">
                    {busy ? t.savingToAccount : problem ? t.listProblem[problem] : " "}
                </p>
            </Screen>
        </div>
    );
}

/** Settings › Your lists: the profile's lists, each with Delete (twice, to be sure). Renaming stays on the phone. */
export function ManageLists() {
    useStore(accountLists);
    useStore(profileId);
    const acc = useStore(account);
    const profile = accountProfile();
    const lists = myLists();
    const [asked, setAsked] = useState<string | null>(null);
    const [busy, setBusy] = useState<string | null>(null);
    const [problem, setProblem] = useState<ListProblem | null>(null);
    if (!profile || !acc.premium) return null;
    return (
        <section>
            <h2>{t.yourLists}</h2>
            {!lists.length ? <p class="muted">{t.noListsYet}</p> : null}
            {lists.map((l) => (
                <div class="row" key={l.id} data-row>
                    <span class="grow">{l.name}</span>
                    <span class="muted">{t.listCount(l.items.length)}</span>
                    <Button
                        label={asked === l.id ? t.deleteListSure : t.deleteList}
                        busy={busy === l.id}
                        onClick={() => {
                            if (asked !== l.id) return void setAsked(l.id);
                            setBusy(l.id);
                            setProblem(null);
                            void changeAccount({ op: "list_delete", profileId: profile.id, listId: l.id }).then((failed) => {
                                setBusy(null);
                                setAsked(null);
                                setProblem(failed);
                            });
                        }}
                    />
                </div>
            ))}
            {problem ? (
                <p class="error" aria-live="polite">
                    {t.listProblem[problem]}
                </p>
            ) : null}
            <p class="muted">{t.listsOnPhone}</p>
        </section>
    );
}
