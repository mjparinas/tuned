// The account page (docs/decisions/accounts.md): devices and seats, buying Premium and seats through Stripe, the
// backup, pairing a TV, signing out and deleting the account. Everything goes through /api with the session cookie.
import { normalizeCode } from "/tv/seal.js";
import { drawNav, showLinkedSection } from "/account/nav.js";
import { enhanceSelect } from "/select.js";

const $ = (id) => document.getElementById(id);
const status = $("status");
const params = new URLSearchParams(location.search);
let me = null;

const errors = {
    setup: "Signing in isn't set up yet.",
    busy: "Too many sign-ins from here. Try again in an hour.",
    failed: "Signing in didn't work. Try again.",
};
const refusals = {
    "already premium": "You already have Premium.",
    "premium first": "Get Premium first; it comes with three seats.",
    "wrong seat kind": "Extra seats match your Premium: one-time seats with lifetime, yearly seats with yearly.",
    "too many seats": "An account can have at most ten seats.",
    "not for sale": "Buying here isn't set up yet.",
    "not found": "That code has run out. The screen shows a new one.",
    "bad code": "That doesn't look like the code on the screen. It's eight letters and numbers.",
    "already sent": "That device has already been sent something. Use the new code it shows.",
    "too many": "Too many tries. Try again in an hour.",
    unavailable: "Something went wrong on our side. Try again in a moment.",
};
const say = (text) => (status.textContent = text ?? "");

/**
 * A message inside the card it belongs to (Melvin, 2026-11-19: errors "should show in the same tile"), red for an
 * error and teal for done. The field it is about, if any, is marked invalid so its outline turns red too.
 */
function tell(id, text, tone = "error", field = null) {
    const line = $(id);
    line.textContent = text ?? "";
    line.dataset.tone = tone;
    if (field) $(field).setAttribute("aria-invalid", String(Boolean(text) && tone === "error"));
}
const clear = (id, field) => tell(id, "", "error", field);

// The same rules the server uses (lib/admin/codes.mjs normalizeCode): letters, numbers and dashes, 4 to 24 long.
const codeLooksRight = (value) => /^[A-Z0-9][A-Z0-9-]{2,22}[A-Z0-9]$/.test(value.trim().toUpperCase().replace(/\s+/g, "-"));

async function call(method, path, body) {
    try {
        const response = await fetch(path, {
            method,
            headers: body ? { "Content-Type": "application/json" } : {},
            body: body ? JSON.stringify(body) : undefined,
        });
        return await response.json();
    } catch {
        return { status: "unavailable" };
    }
}

const when = (ms) => new Date(ms).toLocaleDateString("en-CA", { year: "numeric", month: "long", day: "numeric" });
const shortWhen = (ms) => new Date(ms).toLocaleDateString("en-CA", { month: "short", day: "numeric" });
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const kinds = { phone: "Phone", tablet: "Tablet", tv: "TV" };
const seatName = { seat_once: "one-time", seat_yearly: "yearly" };

function render() {
    $("signed-out").hidden = Boolean(me);
    $("signed-in").hidden = !me;
    $("account-nav").hidden = !me;
    document.querySelector("main").classList.toggle("signed-in", Boolean(me));
    const tv = params.get("tv");
    $("tv").hidden = !(me && tv);
    $("tv-code").textContent = tv ?? "";
    if (!me) {
        if (tv) {
            $("sign-in").href = `/api/auth/google?tv=${encodeURIComponent(tv)}`;
            $("sign-in-apple").href = `/api/auth/apple?tv=${encodeURIComponent(tv)}`;
        }
        return;
    }
    $("who").textContent = me.name || me.email;
    $("avatar").textContent = (me.name || me.email || "?").trim().charAt(0).toUpperCase();
    $("plan").textContent = me.premium
        ? `Tuned Premium, ${me.premium === "lifetime" ? "for life" : "yearly"}. Signed in as ${me.email}.`
        : `Free. Signed in as ${me.email}.`;

    $("seats").textContent = me.premium
        ? `${me.seats.used} of ${me.seats.limit} Premium seats in use. A device unused for 60 days gives its seat back.`
        : "Premium isn't on this account, so devices don't use seats.";
    const list = $("device-list");
    list.replaceChildren(
        ...me.devices.map((d) => {
            const row = document.createElement("li");
            row.className = "device";
            const icon = document.createElement("span");
            icon.className = `device-icon ${d.kind in kinds ? d.kind : "phone"}`;
            icon.setAttribute("aria-hidden", "true");
            const text = document.createElement("div");
            const name = document.createElement("div");
            name.className = "device-name";
            name.textContent = d.name;
            const line = document.createElement("p");
            line.className = "small";
            line.textContent = `${kinds[d.kind] ?? "Device"} · ${d.seated ? "uses a seat" : "no seat"} · last used ${when(d.lastSeen)}`;
            text.append(name, line);
            const remove = document.createElement("button");
            remove.type = "button";
            remove.className = "plain";
            remove.textContent = "Remove";
            remove.setAttribute("aria-label", `Remove ${d.name}`);
            remove.onclick = async () => {
                const answer = await call("POST", "/api/account/devices", { remove: d.id });
                if (answer.status === "ok") {
                    me = answer;
                    render();
                    tell("devices-message", `${d.name} was removed and signed out.`, "ok");
                } else tell("devices-message", refusals[answer.status] ?? refusals.unavailable);
            };
            row.append(icon, text, remove);
            return row;
        }),
    );
    if (!me.devices.length) {
        const empty = document.createElement("li");
        empty.className = "empty";
        empty.textContent = "No devices are signed in yet. Sign in in Tuned, under Settings › Account and backup.";
        list.append(empty);
    }
    $("seat-bar").hidden = !me.premium;
    $("seat-bar").firstElementChild.style.width = me.premium
        ? `${Math.min(100, (100 * me.seats.used) / Math.max(1, me.seats.limit))}%`
        : "0";

    // At a glance: the plan, seats, playlists and backup, each a link to its card.
    $("glance-plan-value").textContent = me.premium ? "Premium" : "Free";
    $("glance-plan-line").textContent = me.premium ? (me.premium === "lifetime" ? "For life" : "Yearly") : "Get Premium";
    $("glance-devices-value").textContent = me.premium ? `${me.seats.used} of ${me.seats.limit}` : String(me.devices.length);
    $("glance-devices-line").textContent = me.premium ? "seats in use" : me.devices.length === 1 ? "device signed in" : "devices signed in";
    $("glance-backup-value").textContent = me.backup ? shortWhen(me.backup.savedAt) : "None yet";
    $("glance-backup-line").textContent = me.backup ? `from ${me.backup.device}` : "Sign in on a device";

    $("buy-premium").hidden = Boolean(me.premium);
    $("buy-seats").hidden = !(me.premium && me.seats.canBuy > 0);
    $("buy-title").textContent = me.premium ? "More seats" : "Tuned Premium";
    $("buy-line").textContent = !me.premium
        ? "Premium comes with three devices. Yearly or once for life."
        : me.seats.canBuy > 0
          ? `Add ${seatName[me.seats.seatKind]} seats to your ${me.premium} Premium, up to ten devices in all.`
          : "Your account has the most seats it can have.";
    const count = $("seat-count");
    count.replaceChildren(
        ...Array.from({ length: me.seats.canBuy }, (_, i) => {
            const option = document.createElement("option");
            option.value = String(i + 1);
            option.textContent = plural(i + 1, "seat", "seats");
            return option;
        }),
    );
    $("billing").hidden = !me.purchases.some((p) => p.source === "stripe");

    $("backup-line").textContent = me.backup
        ? `Saved ${when(me.backup.savedAt)} from ${me.backup.device}. Your devices keep it up to date and restore from it.`
        : "No backup yet. Sign in on a device in Tuned, under Settings › Account and backup.";
    $("delete-backup").hidden = !me.backup;
}

async function load() {
    const answer = await call("GET", "/api/account/me");
    if (answer.status === "ok") me = answer;
    else if (answer.status === "signed out") me = null;
    else say(refusals.unavailable);
    render();
    if (me && !navDrawn) {
        navDrawn = true;
        drawNav($("account-nav"), "account");
        countPlaylists();
    }
    await showFeatures();
    showLinkedSection();
}
let navDrawn = false;

/** How many playlists the account keeps, for the Playlists tile. If it can't be read, the tile just says Manage. */
async function countPlaylists() {
    const answer = await call("GET", "/api/account/library");
    if (answer.status !== "ok" || !Array.isArray(answer.playlists)) return;
    const n = answer.playlists.length;
    $("glance-playlists-value").textContent = n ? plural(n, "playlist", "playlists") : "None yet";
    $("glance-playlists-line").textContent = n ? "and your settings" : "Add one";
}

async function checkout(product, quantity = 1) {
    tell("buy-message", "Opening checkout…", "ok");
    const answer = await call("POST", "/api/stripe/checkout", { product, quantity });
    if (answer.status === "ok") location.href = answer.url;
    else tell("buy-message", refusals[answer.status] ?? refusals.unavailable);
}

for (const button of document.querySelectorAll("[data-product]")) button.onclick = () => checkout(button.dataset.product);
enhanceSelect($("seat-count"));
$("buy-seat").onclick = () => checkout(me.seats.seatKind, Number($("seat-count").value));
$("billing").onclick = async () => {
    const answer = await call("POST", "/api/stripe/portal");
    if (answer.status === "ok") location.href = answer.url;
    else tell("buy-message", refusals.unavailable);
};

// Codes made on the admin's Codes tab (docs/decisions/admin.md).
const codeAnswers = {
    ok: "Done. It's on your account now.",
    "not found": "That code doesn't work. Check it and try again.",
    expired: "That code has expired.",
    "used up": "That code has been used up.",
    "already used": "You've already used that code.",
    "already premium": "You already have Premium, so this code can't add it again.",
    "premium first": "Seats from a code need Premium first.",
    "too many seats": "An account can have at most ten seats.",
    "too many": "Too many tries. Try again in an hour.",
};
$("code-form").onsubmit = async (event) => {
    event.preventDefault();
    const code = $("code").value;
    if (!code.trim()) return tell("code-message", "Type the code first.", "error", "code");
    if (!codeLooksRight(code)) {
        return tell("code-message", "That isn't the right format. A code is letters and numbers, like TUNED-7KQM-2XPA.", "error", "code");
    }
    const answer = await call("POST", "/api/codes/redeem", { code });
    tell("code-message", codeAnswers[answer.status] ?? refusals.unavailable, answer.status === "ok" ? "ok" : "error", "code");
    if (answer.status === "ok") {
        $("code").value = "";
        load();
    }
};

async function pair(code, where, field = null) {
    if (!String(code ?? "").trim()) return tell(where, "Type the code on the screen first.", "error", field);
    if (!normalizeCode(code)) return tell(where, refusals["bad code"], "error", field);
    const answer = await call("POST", "/api/account/tv", { code });
    const ok = answer.status === "ok";
    tell(
        where,
        ok ? "Done. Your device is signing in now." : (refusals[answer.status] ?? refusals.unavailable),
        ok ? "ok" : "error",
        field,
    );
    return ok;
}
$("pair-form").onsubmit = (event) => {
    event.preventDefault();
    pair($("pair-code").value, "pair-message", "pair-code");
};
$("tv-yes").onclick = async () => {
    if (await pair(params.get("tv"), "tv-message")) {
        $("tv").hidden = true;
        tell("pair-message", "Done. Your device is signing in now.", "ok");
    }
};
$("tv-no").onclick = () => ($("tv").hidden = true);

$("sign-out").onclick = async () => {
    await call("POST", "/api/auth/signout", {});
    me = null;
    render();
    say("Signed out.");
    $("sign-in").focus();
};
// Deleting the account or the backup asks in a pop-up that only goes ahead once "delete" is typed (Melvin, 2026-11-19
// for the account, 2026-11-20 for the backup: "it should also have a pop up that makes you type delete first").
const dialog = $("delete-dialog");
const typedDelete = () => $("delete-word").value.trim().toLowerCase() === "delete";
const deletions = {
    account: {
        title: "Delete your account?",
        text: "This deletes your account, devices and backup, and cancels a yearly plan bought here. A plan bought in Google Play is cancelled in Play. It can't be undone.",
        button: "Delete account",
        async run() {
            const answer = await call("POST", "/api/account/delete", {});
            if (answer.status !== "ok") return false;
            me = null;
            render();
            say("Your account was deleted.");
            return true;
        },
    },
    backup: {
        title: "Delete your backup?",
        text: "This deletes the copy of your playlists, logins, settings and lists saved in your account. Your devices keep theirs, and a signed-in device saves a new one next time it opens. It can't be undone.",
        button: "Delete backup",
        async run() {
            const answer = await call("DELETE", "/api/backup", {});
            if (answer.status !== "ok") return false;
            await load();
            tell("backup-message", "Your backup was deleted. A signed-in device will save a new one next time it opens.", "ok");
            return true;
        },
    },
};
let deleting = deletions.account;
function askToDelete(what) {
    deleting = deletions[what];
    $("delete-title").textContent = deleting.title;
    $("delete-text").textContent = deleting.text;
    $("delete-confirm").textContent = deleting.button;
    $("delete-word").value = "";
    $("delete-confirm").disabled = true;
    clear("delete-message");
    dialog.showModal();
    $("delete-word").focus();
}
$("delete-account").onclick = () => askToDelete("account");
$("delete-backup").onclick = () => askToDelete("backup");
$("delete-word").oninput = () => ($("delete-confirm").disabled = !typedDelete());
$("delete-cancel").onclick = () => dialog.close();
dialog.onclick = (event) => {
    if (event.target === dialog) dialog.close();
};
$("delete-form").onsubmit = async (event) => {
    event.preventDefault();
    if (!typedDelete()) return;
    $("delete-confirm").disabled = true;
    let done = false;
    try {
        done = await deleting.run();
    } catch {
        done = false;
    }
    if (done) dialog.close();
    else {
        $("delete-confirm").disabled = false;
        tell("delete-message", refusals.unavailable);
    }
};

// Typing again clears an old message, so a red line never sits under a fixed code.
$("code").oninput = () => clear("code-message", "code");
$("pair-code").oninput = () => clear("pair-message", "pair-code");

// What Tuned does, from the same list as the landing page (/features.json, built from site/features.mjs), so the two
// never disagree. Read once, after sign-in; each group opens on a tap so the page stays short.
let featuresShown = false;
async function showFeatures() {
    if (featuresShown || !me) return;
    featuresShown = true;
    let groups = [];
    try {
        groups = (await (await fetch("/features.json")).json()).groups ?? [];
    } catch {
        groups = [];
    }
    $("features").hidden = groups.length === 0;
    $("feature-groups").replaceChildren(
        ...groups.map((group, index) => {
            const details = document.createElement("details");
            details.open = index === 0;
            const summary = document.createElement("summary");
            summary.textContent = group.title;
            const count = document.createElement("span");
            count.className = "count";
            count.textContent = String(group.items.length);
            summary.append(count);
            const list = document.createElement("div");
            list.className = "features";
            for (const item of group.items) {
                const row = document.createElement("div");
                const title = document.createElement("b");
                title.textContent = item.title;
                if (item.premium) {
                    const tag = document.createElement("span");
                    tag.className = "tag";
                    tag.textContent = "Premium";
                    title.append(tag);
                }
                const body = document.createElement("span");
                body.textContent = item.body;
                row.append(title, body);
                list.append(row);
            }
            details.append(summary, list);
            return details;
        }),
    );
}

if (params.get("error")) say(errors[params.get("error")] ?? errors.failed);
if (params.get("bought")) say("Thanks. It can take a minute to show here.");
load();
