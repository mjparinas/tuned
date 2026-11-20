// The page for freeing Premium devices with a Google Play order number (docs/decisions/premium.md). A file of its own,
// not inline, so the site's Content-Security-Policy can refuse inline scripts.
const status = document.getElementById("status");
const list = document.getElementById("list");
const card = document.getElementById("devices");
const all = document.getElementById("all");
let order = "";
let wait = 0;

const messages = {
    "bad order": "That doesn't look like an order number. It looks like GPA.1234-5678-9012-34567.",
    "not found": "No Premium devices found for that order number. Check the receipt email, or open Tuned on a device once first.",
    "too many": "Too many tries. Try again in an hour.",
    wait: "Devices can be removed here once every 30 days.",
    unavailable: "Something went wrong on our side. Try again in a moment.",
};

async function ask(path, body) {
    const response = await fetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
    return response.json().catch(() => ({ status: "unavailable" }));
}

function show(answer) {
    if (answer.status !== "ok") {
        status.textContent = messages[answer.status] ?? messages.unavailable;
        return;
    }
    wait = answer.nextRemovalDays;
    list.replaceChildren();
    for (const device of answer.devices) {
        const item = document.createElement("li");
        const label = document.createElement("span");
        label.textContent = device.name;
        const when = document.createElement("small");
        when.textContent = "Last used " + new Date(device.lastSeen).toLocaleDateString("en-CA", { dateStyle: "medium" });
        label.append(when);
        const remove = document.createElement("button");
        remove.className = "plain";
        remove.type = "button";
        remove.textContent = "Remove";
        remove.setAttribute("aria-label", "Remove " + device.name);
        remove.disabled = wait > 0;
        remove.onclick = () => removeDevice(device.id, device.name);
        item.append(label, remove);
        list.append(item);
    }
    all.disabled = wait > 0 || answer.devices.length === 0;
    card.hidden = false;
    status.textContent =
        answer.devices.length === 0
            ? "No devices are using Premium. Open Tuned on the device you want and Premium turns on."
            : wait > 0
              ? "You can remove devices here again in " + wait + (wait === 1 ? " day." : " days.")
              : "You can remove one device, or every device, once every 30 days.";
}

async function removeDevice(id, name) {
    const what = id === "all" ? "every device" : name;
    if (!confirm("Remove " + what + "? You won't be able to remove another here for 30 days.")) return;
    show(await ask("/api/order/remove", { order, remove: id }));
}

document.getElementById("find").onsubmit = async (event) => {
    event.preventDefault();
    order = document.getElementById("order").value;
    status.textContent = "Looking…";
    card.hidden = true;
    show(await ask("/api/order/devices", { order }));
};
all.onclick = () => removeDevice("all");
