// The account page (docs/decisions/accounts.md): devices and seats, buying Premium and seats through Stripe, the
// backup, pairing a TV, signing out and deleting the account. Everything goes through /api with the session cookie.
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
                    say(`${d.name} was removed and signed out.`);
                } else say(refusals[answer.status] ?? refusals.unavailable);
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
    say("Opening checkout…");
    const answer = await call("POST", "/api/stripe/checkout", { product, quantity });
    if (answer.status === "ok") location.href = answer.url;
    else say(refusals[answer.status] ?? refusals.unavailable);
}

for (const button of document.querySelectorAll("[data-product]")) button.onclick = () => checkout(button.dataset.product);
$("buy-seat").onclick = () => checkout(me.seats.seatKind, Number($("seat-count").value));
$("billing").onclick = async () => {
    const answer = await call("POST", "/api/stripe/portal");
    if (answer.status === "ok") location.href = answer.url;
    else say(refusals.unavailable);
};

async function pair(code) {
    const answer = await call("POST", "/api/account/tv", { code });
    say(answer.status === "ok" ? "Done. Your device is signing in now." : (refusals[answer.status] ?? refusals.unavailable));
    return answer.status === "ok";
}
$("pair").onclick = () => pair($("pair-code").value);
$("tv-yes").onclick = async () => {
    if (await pair(params.get("tv"))) $("tv").hidden = true;
};
$("tv-no").onclick = () => ($("tv").hidden = true);

$("delete-backup").onclick = async () => {
    const answer = await call("DELETE", "/api/backup", {});
    if (answer.status === "ok") {
        say("Your backup was deleted. A signed-in device will save a new one next time it opens.");
        await load();
    } else say(refusals.unavailable);
};
$("sign-out").onclick = async () => {
    await call("POST", "/api/auth/signout", {});
    me = null;
    render();
    say("Signed out.");
};
let confirmDelete = false;
$("delete-account").onclick = async () => {
    if (!confirmDelete) {
        confirmDelete = true;
        $("delete-line").hidden = false;
        return;
    }
    const answer = await call("POST", "/api/account/delete", {});
    if (answer.status === "ok") {
        me = null;
        render();
        say("Your account was deleted.");
    } else say(refusals.unavailable);
};

if (params.get("error")) say(errors[params.get("error")] ?? errors.failed);
if (params.get("bought")) say("Thanks. It can take a minute to show here.");
load();
