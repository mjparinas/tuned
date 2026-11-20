// The Playlists and settings page (docs/decisions/accounts.md): the playlists and settings in the account's backup,
// changed through /api/account/library with the session cookie. Every device takes the change on its next sync.
// Passwords and links never come back from the server; editing leaves a blank one as it was.

import { drawNav, flagOn, showLinkedSection } from "/account/nav.js";

const $ = (id) => document.getElementById(id);
let library = null;
let editing = null;

const refusals = {
    "server needed": "Type the server's address.",
    "bad server": "That doesn't look like a server address, such as http://provider.example:8080.",
    "username needed": "Type the username.",
    "password needed": "Type the password.",
    "link needed": "Paste the playlist link.",
    "bad link": "That doesn't look like a web address. It starts with http:// or https://.",
    "portal needed": "Type the portal's address.",
    "bad portal": "That doesn't look like a portal address, such as http://portal.example/c/.",
    "bad mac": "A MAC address is 12 letters and numbers, such as 00:1A:79:12:34:56.",
    "bad guide": "The guide link must be a web address starting with http:// or https://.",
    "too long": "That's longer than any address Tuned can save.",
    "already saved": "That playlist is already saved.",
    "too many playlists": "An account can keep up to 50 playlists.",
    "last playlist": "Your devices need at least one playlist. Add another before removing this one.",
    gone: "That playlist was changed on another device. The list now shows it as it is.",
    "bad setting": "That setting couldn't be saved. Reload the page and try again.",
    "too many": "Too many changes this hour. Try again later.",
    "too large": "Your backup is too large to change here.",
    busy: "Another device was saving at the same moment. Try again.",
    "signed out": "You're signed out. Sign in again on your account page.",
    unavailable: "Something went wrong on our side. Try again in a moment.",
};
const kindNames = { xtream: "Xtream login", link: "M3U link", stalker: "MAC portal", file: "File" };
const kindLines = {
    xtream: "The server, username and password from your provider.",
    link: "A link to an M3U playlist, or an Xtream get.php link.",
    stalker: "A Stalker or Ministra portal, signed in with a MAC address, as a MAG box does.",
};

/** A message in the card it belongs to, red for an error and teal for done, with the field it's about outlined. */
function tell(id, text, tone = "error", field = null) {
    const line = $(id);
    line.textContent = text ?? "";
    line.dataset.tone = tone;
    document.querySelectorAll("#editor-form input[aria-invalid]").forEach((i) => i.removeAttribute("aria-invalid"));
    if (field && text && tone === "error") {
        $(field)?.setAttribute("aria-invalid", "true");
        $(field)?.focus();
    }
}

async function call(method, body) {
    try {
        const response = await fetch("/api/account/library", {
            method,
            headers: body ? { "Content-Type": "application/json" } : {},
            body: body ? JSON.stringify(body) : undefined,
        });
        return await response.json();
    } catch {
        return { status: "unavailable" };
    }
}

const when = (ms) => new Date(ms).toLocaleString("en-CA", { month: "long", day: "numeric", hour: "numeric", minute: "2-digit" });

function playlistRow(p) {
    const row = document.createElement("div");
    row.className = "row";
    const text = document.createElement("div");
    const name = document.createElement("div");
    name.textContent = p.name || p.detail || kindNames[p.kind];
    const line = document.createElement("p");
    line.className = "small";
    const parts = [kindNames[p.kind]];
    if (p.name && p.detail) parts.push(p.detail);
    if (p.guide) parts.push(`guide from ${p.guideHost}`);
    line.textContent = parts.join(" · ");
    text.append(name, line);
    const buttons = document.createElement("div");
    buttons.className = "row-actions";
    // The provider check (docs/decisions/web.md): Xtream logins and M3U links, which a browser can try.
    if (checkOn && (p.kind === "xtream" || p.kind === "link")) {
        const check = document.createElement("a");
        check.className = "button quiet";
        check.href = `/check?playlist=${p.id}`;
        check.textContent = "Check";
        check.setAttribute("aria-label", `Check ${name.textContent} in this browser`);
        check.dataset.track = "check-playlist";
        buttons.append(check);
    }
    if (p.kind !== "file") {
        const edit = document.createElement("button");
        edit.type = "button";
        edit.className = "quiet";
        edit.textContent = "Edit";
        edit.setAttribute("aria-label", `Edit ${name.textContent}`);
        edit.onclick = () => openEditor(p);
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "plain";
        remove.textContent = "Remove";
        remove.setAttribute("aria-label", `Remove ${name.textContent}`);
        remove.onclick = () => askRemove(p, name.textContent);
        buttons.append(edit, remove);
    }
    row.append(text, buttons);
    return row;
}

function settingRow(s) {
    const row = document.createElement("div");
    row.className = "row setting";
    const id = `setting-${s.key}`;
    const label = document.createElement("label");
    label.htmlFor = id;
    label.textContent = s.label;
    let control;
    if (s.type === "bool") {
        control = document.createElement("input");
        control.type = "checkbox";
        control.className = "switch";
        control.setAttribute("role", "switch");
        control.checked = s.value;
        control.onchange = () => saveSetting(s, control.checked, control);
    } else {
        control = document.createElement("select");
        for (const [value, text] of s.options) {
            const option = document.createElement("option");
            option.value = String(value);
            option.textContent = text;
            option.selected = value === s.value;
            control.append(option);
        }
        control.onchange = () => saveSetting(s, s.type === "int" ? Number(control.value) : control.value, control);
    }
    control.id = id;
    row.append(label, control);
    return row;
}

async function saveSetting(s, value, control) {
    const card = control.closest("section");
    const message = card.querySelector(".message").id;
    control.disabled = true;
    const answer = await call("POST", { op: "settings", values: { [s.key]: value } });
    control.disabled = false;
    if (answer.status === "ok") {
        library = answer;
        tell(message, `Saved. Your devices change "${s.label}" when they next sync.`, "ok");
    } else {
        // Put the control back as it was, so the page never shows a setting that wasn't saved.
        if (control.type === "checkbox") control.checked = !value;
        else control.value = String(s.value);
        tell(message, refusals[answer.status] ?? refusals.unavailable);
    }
}

function render() {
    $("signed-out").hidden = Boolean(library);
    $("signed-in").hidden = !library;
    $("account-nav").hidden = !library;
    document.querySelector("main").classList.toggle("signed-in", Boolean(library));
    if (!library) return;
    const list = $("playlist-list");
    list.replaceChildren(...library.playlists.map(playlistRow));
    if (!library.playlists.length) {
        const empty = document.createElement("p");
        empty.textContent = "No playlists yet. Add one and your devices pick it up.";
        list.append(empty);
    }
    $("playlists-line").textContent = library.exists
        ? "Tuned checks a login on your device the next time it refreshes. This website never connects to your provider."
        : "No device has backed up to this account yet. What you add here waits for the first device that signs in.";
    $("refresh").hidden = !library.playlists.length;
    $("settings").replaceChildren(
        ...library.settings.map((group) => {
            const card = document.createElement("section");
            card.className = "card";
            card.id = groupId(group.group);
            const title = document.createElement("h2");
            title.id = `${card.id}-title`;
            card.setAttribute("aria-labelledby", title.id);
            title.textContent = group.group;
            const rows = document.createElement("div");
            rows.append(...group.settings.map(settingRow));
            const message = document.createElement("p");
            message.className = "message";
            message.id = `settings-${group.group.toLowerCase()}-message`;
            message.setAttribute("role", "status");
            message.setAttribute("aria-live", "polite");
            card.append(title, rows, message);
            return card;
        }),
    );
}

const groupId = (name) => `settings-${name.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;
let navDrawn = false;

const kind = () => document.querySelector('input[name="kind"]:checked').value;

function showKind() {
    const k = kind();
    document.querySelectorAll("[data-kind]").forEach((part) => (part.hidden = !part.dataset.kind.split(" ").includes(k)));
    $("server-label").textContent = k === "stalker" ? "Portal address" : "Server";
    $("server").placeholder = k === "stalker" ? "http://portal.example/c/" : "http://provider.example:8080";
    $("kind-line").textContent = kindLines[k];
    const same = editing && editing.kind === k;
    $("password-hint").hidden = !same;
    $("link").placeholder = same && k === "link" ? "Leave blank to keep the saved link" : "https://provider.example/playlist.m3u";
}

function openEditor(p = null) {
    editing = p;
    const form = $("editor-form");
    form.reset();
    tell("editor-message", "");
    $("editor-title").textContent = p ? `Edit ${p.name || p.detail}` : "Add a playlist";
    document.querySelector(`input[name="kind"][value="${p?.kind ?? "xtream"}"]`).checked = true;
    if (p) {
        $("name").value = p.name;
        $("server").value = p.server;
        $("username").value = p.username;
        $("mac").value = p.mac;
    }
    $("guide").placeholder = p?.guide ? `Leave blank to keep the guide from ${p.guideHost}` : "https://provider.example/epg.xml";
    $("clear-guide-row").hidden = !p?.guide;
    showKind();
    $("editor").hidden = false;
    $("add").hidden = true;
    $("editor").scrollIntoView({ behavior: "smooth", block: "start" });
    (p ? $("name") : document.querySelector('input[name="kind"]:checked')).focus();
}

function closeEditor() {
    editing = null;
    $("editor").hidden = true;
    $("add").hidden = false;
}

async function save(event) {
    event.preventDefault();
    const k = kind();
    const playlist = { kind: k, name: $("name").value, guide: $("guide").value, clearGuide: $("clear-guide").checked };
    if (k === "xtream")
        Object.assign(playlist, { server: $("server").value, username: $("username").value, password: $("password").value });
    if (k === "link") playlist.link = $("link").value;
    if (k === "stalker") Object.assign(playlist, { server: $("server").value, mac: $("mac").value });
    // The same checks the server makes, so a typo says so at once.
    if ((k === "xtream" || k === "stalker") && !playlist.server.trim())
        return tell("editor-message", refusals[k === "xtream" ? "server needed" : "portal needed"], "error", "server");
    if (k === "xtream" && !playlist.username.trim()) return tell("editor-message", refusals["username needed"], "error", "username");
    if (k === "xtream" && !playlist.password && editing?.kind !== "xtream")
        return tell("editor-message", refusals["password needed"], "error", "password");
    if (k === "link" && !playlist.link.trim() && editing?.kind !== "link")
        return tell("editor-message", refusals["link needed"], "error", "link");
    if (k === "stalker" && playlist.mac.replace(/[^0-9a-f]/gi, "").length !== 12)
        return tell("editor-message", refusals["bad mac"], "error", "mac");
    $("save").disabled = true;
    const answer = await call("POST", editing ? { op: "edit", id: editing.id, playlist } : { op: "add", playlist });
    $("save").disabled = false;
    if (answer.status === "ok") {
        const was = editing;
        library = answer;
        closeEditor();
        render();
        tell(
            "playlists-message",
            was ? "Saved. Your devices pick up the change when they next sync." : "Added. Your devices pick it up when they next sync.",
            "ok",
        );
    } else {
        tell("editor-message", refusals[answer.status] ?? refusals.unavailable, "error", answer.field);
        if (answer.status === "gone") load();
    }
}

const dialog = $("remove-dialog");
let removing = null;
function askRemove(p, name) {
    removing = p;
    $("remove-title").textContent = `Remove ${name}?`;
    $("remove-line").textContent =
        "It's removed from every device signed in to your account. A device watching it switches to your next playlist.";
    tell("remove-message", "");
    dialog.showModal();
}
$("remove-cancel").onclick = () => dialog.close();
dialog.onclick = (event) => {
    if (event.target === dialog) dialog.close();
};
$("remove-confirm").onclick = async () => {
    $("remove-confirm").disabled = true;
    const answer = await call("POST", { op: "remove", id: removing.id });
    $("remove-confirm").disabled = false;
    if (answer.status === "ok") {
        library = answer;
        dialog.close();
        closeEditor();
        render();
        tell("playlists-message", "Removed. Your devices drop it when they next sync.", "ok");
    } else tell("remove-message", refusals[answer.status] ?? refusals.unavailable);
};

$("add").onclick = () => openEditor();
$("cancel").onclick = closeEditor;
$("editor-form").onsubmit = save;
document.querySelectorAll('input[name="kind"]').forEach((r) => (r.onchange = showKind));
$("refresh").onclick = async () => {
    $("refresh").disabled = true;
    const answer = await call("POST", { op: "refresh" });
    $("refresh").disabled = false;
    if (answer.status === "ok") {
        library = answer;
        tell("playlists-message", "Asked. Each device downloads its playlist again when it next syncs.", "ok");
    } else tell("playlists-message", refusals[answer.status] ?? refusals.unavailable);
};

let checkOn = false;

async function load() {
    checkOn = await flagOn("web_player");
    const answer = await call("GET");
    if (answer.status === "ok") library = answer;
    else if (answer.status === "signed out") library = null;
    else $("status").textContent = refusals.unavailable;
    render();
    if (library && !navDrawn) {
        navDrawn = true;
        drawNav($("account-nav"), "playlists", [["playlists", "Playlists"], ...library.settings.map((g) => [groupId(g.group), g.group])]);
        showLinkedSection();
    }
    if (library?.refreshAskedAt) $("refresh").title = `Last asked ${when(library.refreshAskedAt)}`;
}
load();
