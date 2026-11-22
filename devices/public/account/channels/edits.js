// Channel rename, logo, favourites and lists on the website (docs/decisions/lists.md, accounts.md). Edits go into the
// account backup through /api/account/library and sync to every device. Android TV leaves rename and logo change to
// phone and web (Melvin, 2026-11-22).

const $ = (id) => document.getElementById(id);

async function call(body) {
    try {
        const response = await fetch("/api/account/library", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        });
        return await response.json();
    } catch {
        return { status: "unavailable" };
    }
}

const refusals = {
    gone: "That was already removed.",
    "bad request": "Check what you typed and try again.",
    "bad logo": "Use an http or https picture link.",
    "too many lists": "An account can keep up to 50 lists per profile.",
    unavailable: "Couldn't reach Tuned. Check your connection and try again.",
    "too many": "Too many changes this hour. Try again later.",
    busy: "Another device was saving. Try again.",
};

/**
 * Applies the account's channel edits, favourites and lists to the channels page.
 * @param {object} opts
 * @param {() => object} opts.getState reads the page's shared state
 * @param {(library: object) => void} opts.onLibrary called after a successful change
 * @param {() => void} opts.redraw redraws the guide/list after a name or logo change
 */
export function wireEdits({ getState, onLibrary, redraw }) {
    let editKind = null; // "name" | "logo" | "list"
    let editChannel = null;
    let editListId = null;

    const profile = () => {
        const state = getState();
        return state.profiles.find((p) => p.id === state.profileId) ?? state.profiles[0] ?? null;
    };

    const display = (c) => {
        const edit = getState().channelEdits[c.id];
        return {
            ...c,
            name: edit?.name || c.name,
            logo: edit?.logo || c.logo,
            favourite: Boolean(c.id && profile()?.favourites?.includes(c.id)),
        };
    };

    function drawLists() {
        const state = getState();
        const box = $("lists");
        const p = profile();
        if (!p) {
            box.replaceChildren();
            $("lists-line").textContent = "Open Tuned on a phone or TV once so your profiles sync here, then manage lists.";
            return;
        }
        if (!p.lists.length) {
            const empty = document.createElement("p");
            empty.className = "small";
            empty.textContent = "No lists yet. Add one, then put channels in it from a channel's details.";
            box.replaceChildren(empty);
            return;
        }
        box.replaceChildren(
            ...p.lists.map((l) => {
                const row = document.createElement("div");
                row.className = "row";
                const text = document.createElement("div");
                const name = document.createElement("div");
                name.textContent = l.name;
                const line = document.createElement("p");
                line.className = "small";
                line.textContent = `${l.items.length} ${l.items.length === 1 ? "channel" : "channels"}`;
                text.append(name, line);
                const buttons = document.createElement("div");
                buttons.className = "actions";
                const rename = document.createElement("button");
                rename.type = "button";
                rename.className = "quiet";
                rename.textContent = "Rename";
                rename.onclick = () => openListRename(l);
                const remove = document.createElement("button");
                remove.type = "button";
                remove.className = "quiet";
                remove.textContent = "Delete";
                remove.onclick = async () => {
                    if (!confirm(`Delete “${l.name}”? Its channels stay in your playlist.`)) return;
                    const answer = await call({ op: "list_delete", profileId: p.id, listId: l.id });
                    if (answer.status === "ok" || answer.profiles) {
                        onLibrary(answer);
                        tell("lists-message", "Deleted. Your devices drop it when they next sync.", "ok");
                        drawLists();
                    } else tell("lists-message", refusals[answer.status] ?? refusals.unavailable);
                };
                buttons.append(rename, remove);
                row.append(text, buttons);
                return row;
            }),
        );
    }

    function drawProfiles() {
        const state = getState();
        const field = $("profile-field");
        const select = $("profile");
        if (state.profiles.length <= 1) {
            field.hidden = true;
            return;
        }
        field.hidden = false;
        select.replaceChildren(...state.profiles.map((p) => new Option(p.name, String(p.id))));
        select.value = String(state.profileId);
    }

    function tell(id, text, tone = "error") {
        const line = $(id);
        if (!line) return;
        line.textContent = text ?? "";
        line.dataset.tone = tone;
    }

    function openEdit(kind, channel, initial, title, label, showOriginal) {
        editKind = kind;
        editChannel = channel;
        editListId = null;
        $("edit-title").textContent = title;
        $("edit-label").textContent = label;
        $("edit-value").value = initial ?? "";
        $("edit-original").hidden = !showOriginal;
        tell("edit-message", "", "ok");
        $("edit-dialog").showModal();
        $("edit-value").focus();
        $("edit-value").select();
    }

    function openListRename(list) {
        editKind = "list";
        editChannel = null;
        editListId = list.id;
        $("edit-title").textContent = "Rename list";
        $("edit-label").textContent = "Name";
        $("edit-value").value = list.name;
        $("edit-original").hidden = true;
        tell("edit-message", "", "ok");
        $("edit-dialog").showModal();
        $("edit-value").focus();
        $("edit-value").select();
    }

    $("edit-form").onsubmit = async (e) => {
        e.preventDefault();
        const value = $("edit-value").value.trim();
        const p = profile();
        let answer;
        if (editKind === "name" && editChannel?.id) {
            if (!value) return tell("edit-message", "Enter a name.");
            answer = await call({ op: "channel_edit", itemId: editChannel.id, name: value });
        } else if (editKind === "logo" && editChannel?.id) {
            if (!value) return tell("edit-message", "Enter a picture link.");
            answer = await call({ op: "channel_edit", itemId: editChannel.id, logo: value });
        } else if (editKind === "list" && p && editListId) {
            if (!value) return tell("edit-message", "Enter a name.");
            answer = await call({ op: "list_rename", profileId: p.id, listId: editListId, name: value });
        } else {
            return tell("edit-message", "Update Tuned on a device so this channel gets an id, then try again.");
        }
        if (answer.status && answer.status !== "ok" && !answer.profiles && !answer.channelEdits) {
            return tell("edit-message", refusals[answer.status] ?? refusals.unavailable);
        }
        onLibrary(answer);
        $("edit-dialog").close();
        drawLists();
        redraw();
    };

    $("edit-original").onclick = async () => {
        if (!editChannel?.id) return;
        const body =
            editKind === "name"
                ? { op: "channel_edit", itemId: editChannel.id, name: "" }
                : { op: "channel_edit", itemId: editChannel.id, logo: "" };
        const answer = await call(body);
        if (answer.status && answer.status !== "ok" && !answer.channelEdits) {
            return tell("edit-message", refusals[answer.status] ?? refusals.unavailable);
        }
        onLibrary(answer);
        $("edit-dialog").close();
        redraw();
    };

    $("list-add").onclick = async () => {
        const name = prompt("Name for the new list");
        if (name == null) return;
        const trimmed = name.trim();
        if (!trimmed) return tell("lists-message", "Enter a name.");
        const p = profile();
        const answer = await call({ op: "list_add", profileId: p?.id, name: trimmed });
        if (answer.status && answer.status !== "ok" && !answer.profiles) {
            return tell("lists-message", refusals[answer.status] ?? refusals.unavailable);
        }
        onLibrary(answer);
        tell("lists-message", "Added. Your devices pick it up when they next sync.", "ok");
        drawLists();
    };

    $("profile").onchange = () => {
        getState().profileId = Number($("profile").value);
        drawLists();
        redraw();
    };

    /**
     * Extra actions on a channel's details dialog: favourite, rename, logo, add to list.
     * @returns {HTMLElement[]}
     */
    function channelButtons(raw) {
        const c = display(raw);
        const buttons = [];
        const p = profile();
        if (!c.id) {
            const tip = document.createElement("p");
            tip.className = "small";
            tip.textContent = "Update Tuned on a device and open this playlist so rename, logo and favourites work here.";
            return [tip];
        }
        const fav = document.createElement("button");
        fav.type = "button";
        fav.className = "quiet";
        fav.textContent = c.favourite ? "Remove from favourites" : "Add to favourites";
        fav.onclick = async () => {
            const answer = await call({ op: "favourite", profileId: p?.id, itemId: c.id, on: !c.favourite });
            if (answer.status && answer.status !== "ok" && !answer.profiles) {
                return tell("show-message", refusals[answer.status] ?? refusals.unavailable);
            }
            onLibrary(answer);
            tell("show-message", c.favourite ? "Removed from favourites." : "Added to favourites.", "ok");
            showDetailsRefresh(raw);
        };
        buttons.push(fav);

        const rename = document.createElement("button");
        rename.type = "button";
        rename.className = "quiet";
        rename.textContent = "Rename";
        rename.onclick = () => openEdit("name", c, c.name, "Rename channel", "Name", Boolean(getState().channelEdits[c.id]?.name));
        buttons.push(rename);

        const logoBtn = document.createElement("button");
        logoBtn.type = "button";
        logoBtn.className = "quiet";
        logoBtn.textContent = "Change logo";
        logoBtn.onclick = () =>
            openEdit(
                "logo",
                c,
                getState().channelEdits[c.id]?.logo || c.logo || "",
                "Change logo",
                "Picture link",
                Boolean(getState().channelEdits[c.id]?.logo),
            );
        buttons.push(logoBtn);

        if (p?.lists?.length) {
            for (const list of p.lists) {
                const on = list.items.includes(c.id);
                const btn = document.createElement("button");
                btn.type = "button";
                btn.className = "quiet";
                btn.textContent = on ? `Remove from ${list.name}` : `Add to ${list.name}`;
                btn.onclick = async () => {
                    const answer = await call({ op: "list_item", profileId: p.id, listId: list.id, itemId: c.id, on: !on });
                    if (answer.status && answer.status !== "ok" && !answer.profiles) {
                        return tell("show-message", refusals[answer.status] ?? refusals.unavailable);
                    }
                    onLibrary(answer);
                    tell("show-message", on ? `Removed from ${list.name}.` : `Added to ${list.name}.`, "ok");
                    drawLists();
                    showDetailsRefresh(raw);
                };
                buttons.push(btn);
            }
        }
        return buttons;
    }

    /** Re-open the details dialog after an edit so buttons refresh. Filled in by the page. */
    let showDetailsRefresh = () => {};

    return {
        display,
        drawLists,
        drawProfiles,
        channelButtons,
        setShowDetailsRefresh(fn) {
            showDetailsRefresh = fn;
        },
        takeLibrary(library) {
            const state = getState();
            state.profiles = library.profiles ?? [];
            state.channelEdits = library.channelEdits ?? {};
            if (!state.profiles.some((p) => p.id === state.profileId)) {
                state.profileId = state.profiles[0]?.id ?? 1;
            }
            drawProfiles();
            drawLists();
        },
    };
}
