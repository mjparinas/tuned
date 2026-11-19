// The account page (docs/decisions/accounts.md): devices and seats, buying Premium and seats through Stripe, the
// backup, pairing a TV, signing out and deleting the account. Everything goes through /api with the session cookie.
import { normalizeCode } from "/tv/seal.js";

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
const kinds = { phone: "Phone", tablet: "Tablet", tv: "TV" };
const seatName = { seat_once: "one-time", seat_yearly: "yearly" };

function render() {
    $("signed-out").hidden = Boolean(me);
    $("signed-in").hidden = !me;
    const tv = params.get("tv");
    $("tv").hidden = !(me && tv);
    $("tv-code").textContent = tv ?? "";
    if (!me) {
        if (tv) $("sign-in").href = `/api/auth/google?tv=${encodeURIComponent(tv)}`;
        return;
    }
    $("who").textContent = me.name || me.email;
    $("plan").textContent = me.premium
        ? `Tuned Premium, ${me.premium === "lifetime" ? "for life" : "yearly"}. Signed in as ${me.email}.`
        : `Free. Signed in as ${me.email}.`;

    $("seats").textContent = me.premium
        ? `${me.seats.used} of ${me.seats.limit} Premium seats in use. A device unused for 60 days gives its seat back.`
        : "Premium isn't on this account, so devices don't use seats.";
    const list = $("devices");
    list.replaceChildren(
        ...me.devices.map((d) => {
            const row = document.createElement("div");
            row.className = "row";
            const text = document.createElement("div");
            const name = document.createElement("div");
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
            row.append(text, remove);
            return row;
        }),
    );
    if (!me.devices.length) list.textContent = "No devices are signed in yet.";

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
            option.textContent = String(i + 1);
            return option;
        }),
    );
    $("billing").hidden = !me.purchases.some((p) => p.source === "stripe");

    $("backup").textContent = me.backup
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
}

async function checkout(product, quantity = 1) {
    tell("buy-message", "Opening checkout…", "ok");
    const answer = await call("POST", "/api/stripe/checkout", { product, quantity });
    if (answer.status === "ok") location.href = answer.url;
    else tell("buy-message", refusals[answer.status] ?? refusals.unavailable);
}

for (const button of document.querySelectorAll("[data-product]")) button.onclick = () => checkout(button.dataset.product);
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
$("redeem").onclick = async () => {
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
$("pair").onclick = () => pair($("pair-code").value, "pair-message", "pair-code");
$("tv-yes").onclick = async () => {
    if (await pair(params.get("tv"), "tv-message")) {
        $("tv").hidden = true;
        tell("pair-message", "Done. Your device is signing in now.", "ok");
    }
};
$("tv-no").onclick = () => ($("tv").hidden = true);

$("delete-backup").onclick = async () => {
    const answer = await call("DELETE", "/api/backup", {});
    if (answer.status === "ok") {
        await load();
        tell("backup-message", "Your backup was deleted. A signed-in device will save a new one next time it opens.", "ok");
    } else tell("backup-message", refusals.unavailable);
};
$("sign-out").onclick = async () => {
    await call("POST", "/api/auth/signout", {});
    me = null;
    render();
    say("Signed out.");
};
// Deleting the account asks in a pop-up that only goes ahead once "delete" is typed (Melvin, 2026-11-19).
const dialog = $("delete-dialog");
const typedDelete = () => $("delete-word").value.trim().toLowerCase() === "delete";
$("delete-account").onclick = () => {
    $("delete-word").value = "";
    $("delete-confirm").disabled = true;
    clear("delete-message");
    dialog.showModal();
    $("delete-word").focus();
};
$("delete-word").oninput = () => ($("delete-confirm").disabled = !typedDelete());
$("delete-cancel").onclick = () => dialog.close();
dialog.onclick = (event) => {
    if (event.target === dialog) dialog.close();
};
$("delete-form").onsubmit = async (event) => {
    event.preventDefault();
    if (!typedDelete()) return;
    $("delete-confirm").disabled = true;
    const answer = await call("POST", "/api/account/delete", {});
    if (answer.status === "ok") {
        dialog.close();
        me = null;
        render();
        say("Your account was deleted.");
    } else {
        $("delete-confirm").disabled = false;
        tell("delete-message", refusals.unavailable);
    }
};

// Typing again clears an old message, so a red line never sits under a fixed code.
$("code").oninput = () => clear("code-message", "code");
$("pair-code").oninput = () => clear("pair-message", "pair-code");

if (params.get("error")) say(errors[params.get("error")] ?? errors.failed);
if (params.get("bought")) say("Thanks. It can take a minute to show here.");
load();
