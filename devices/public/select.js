// Tuned's own dropdown (docs/decisions/website.md › Dropdowns; Melvin, 2026-11-21: "something about this dropdown
// looks ugly, can you make it our own custom component"). It dresses a native <select> rather than replacing it: the
// <select> stays in the page, hidden, and keeps the value, the options and the change event, so a page's own code
// (`.value`, `.onchange`, `.replaceChildren(new Option(…))`, `.disabled`) works unchanged and the dropdown follows it.
//
// It is the WAI-ARIA "select-only combobox": the button is role="combobox" and keeps the focus, the list is a
// role="listbox" whose highlighted option is named by aria-activedescendant. Keys, as a native select's:
//   closed: ↓ ↑ Enter Space Alt+↓ open it; Home and End open it on the first or last; typing opens it on a match.
//   open:   ↓ ↑ Home End Page Up/Down move; Enter, Space, Tab and Alt+↑ choose; Escape closes without choosing;
//           typing jumps to the next option that starts with what was typed.

const PAGE = 10;
let nextId = 0;

/** Dresses every <select> under `root` that isn't dressed yet. */
export function enhanceSelects(root = document) {
    for (const select of root.querySelectorAll("select:not([data-tselect])")) enhanceSelect(select);
}

/** Dresses every <select> under `root` now and each one added later, for pages that draw their fields as they go. */
export function watchSelects(root = document.body) {
    enhanceSelects(root);
    new MutationObserver(() => enhanceSelects(root)).observe(root, { childList: true, subtree: true });
}

/**
 * Dresses one <select>. `label` is its <label> when the select isn't in the page yet (so `select.labels` is empty).
 * Returns the dropdown's outer element, which takes the select's place.
 */
export function enhanceSelect(select, { label } = {}) {
    if (select.dataset.tselect) return select.parentElement;
    select.dataset.tselect = "on";
    const uid = `tselect-${++nextId}`;
    if (!select.id) select.id = `${uid}-native`;

    const wrap = document.createElement("div");
    wrap.className = "tselect";
    const button = document.createElement("div");
    button.className = "tselect-button";
    button.id = `${uid}-button`;
    button.tabIndex = 0;
    button.setAttribute("role", "combobox");
    button.setAttribute("aria-haspopup", "listbox");
    button.setAttribute("aria-expanded", "false");
    button.setAttribute("aria-controls", `${uid}-list`);
    const valueText = document.createElement("span");
    valueText.className = "tselect-value";
    button.append(valueText, chevron());
    const list = document.createElement("div");
    list.className = "tselect-list";
    list.id = `${uid}-list`;
    list.tabIndex = -1;
    list.setAttribute("role", "listbox");
    list.hidden = true;

    select.replaceWith(wrap);
    wrap.append(select, button, list);
    select.hidden = true;
    select.tabIndex = -1;
    select.setAttribute("aria-hidden", "true");

    // The name comes from the select's label, or its own aria-label; the label's click focuses the dropdown.
    const named = label ?? select.labels?.[0];
    if (named?.contains(wrap)) {
        // A label wrapped round the field (<label>Gives <select>) would read the chosen option into the name too.
        const name = [...named.childNodes]
            .filter((n) => n.nodeType === Node.TEXT_NODE)
            .map((n) => n.textContent)
            .join(" ")
            .trim();
        button.setAttribute("aria-label", name);
        list.setAttribute("aria-label", name);
    } else if (named) {
        if (!named.id) named.id = `${uid}-label`;
        button.setAttribute("aria-labelledby", named.id);
        list.setAttribute("aria-labelledby", named.id);
        named.addEventListener("click", (e) => {
            e.preventDefault();
            button.focus();
        });
    } else {
        const name = select.getAttribute("aria-label") ?? "";
        button.setAttribute("aria-label", name);
        list.setAttribute("aria-label", name);
    }

    let active = -1;
    let typed = "";
    let typedAt = 0;
    const options = () => [...select.options];
    const isOpen = () => !list.hidden;
    const disabled = () => select.disabled;

    /** Draws the button and the list from the select, whenever the select changes. */
    function render() {
        const all = options();
        const chosen = all[select.selectedIndex];
        valueText.textContent = chosen?.text ?? "";
        button.setAttribute("aria-disabled", String(disabled()));
        wrap.classList.toggle("is-disabled", disabled());
        for (const attr of ["aria-invalid", "aria-describedby"]) {
            const v = select.getAttribute(attr);
            if (v === null) button.removeAttribute(attr);
            else button.setAttribute(attr, v);
        }
        list.replaceChildren(
            ...all.map((o, i) => {
                const row = document.createElement("div");
                row.className = "tselect-option";
                row.id = `${uid}-option-${i}`;
                row.setAttribute("role", "option");
                row.setAttribute("aria-selected", String(i === select.selectedIndex));
                if (o.disabled) row.setAttribute("aria-disabled", "true");
                row.dataset.index = String(i);
                const text = document.createElement("span");
                text.textContent = o.text;
                row.append(text, tick());
                return row;
            }),
        );
        if (isOpen()) highlight(Math.min(active, all.length - 1));
        if (disabled() && isOpen()) close();
    }

    function highlight(i, scroll = true) {
        const rows = list.children;
        if (active >= 0 && rows[active]) rows[active].removeAttribute("data-active");
        active = i;
        const row = rows[i];
        if (!row) {
            button.removeAttribute("aria-activedescendant");
            return;
        }
        row.setAttribute("data-active", "");
        button.setAttribute("aria-activedescendant", row.id);
        if (scroll) row.scrollIntoView({ block: "nearest" });
    }

    function open(at = select.selectedIndex) {
        if (disabled() || isOpen() || !options().length) return;
        list.hidden = false;
        button.setAttribute("aria-expanded", "true");
        wrap.classList.add("is-open");
        place();
        highlight(Math.max(0, at));
    }

    function close() {
        if (!isOpen()) return;
        list.hidden = true;
        button.setAttribute("aria-expanded", "false");
        button.removeAttribute("aria-activedescendant");
        wrap.classList.remove("is-open", "opens-up", "opens-left");
    }

    function choose(i) {
        const o = options()[i];
        if (!o || o.disabled) return;
        const changed = i !== select.selectedIndex;
        select.selectedIndex = i;
        close();
        if (changed) {
            select.dispatchEvent(new Event("input", { bubbles: true }));
            select.dispatchEvent(new Event("change", { bubbles: true }));
        }
    }

    /** Opens up rather than down when there isn't room below, and lines up on the right when it would run off. */
    function place() {
        wrap.classList.remove("opens-up", "opens-left");
        const box = button.getBoundingClientRect();
        const listBox = list.getBoundingClientRect();
        const below = window.innerHeight - box.bottom;
        if (listBox.height > below - 8 && box.top > below) wrap.classList.add("opens-up");
        if (box.left + listBox.width > document.documentElement.clientWidth - 8) wrap.classList.add("opens-left");
    }

    /** The next enabled option from `from`, `step` at a time, stopping at the ends. */
    function move(from, step) {
        const all = options();
        let i = Math.min(Math.max(from + step, 0), all.length - 1);
        const dir = Math.sign(step) || 1;
        while (all[i]?.disabled && i + dir >= 0 && i + dir < all.length) i += dir;
        return all[i]?.disabled ? from : i;
    }

    function typeAhead(key) {
        const now = Date.now();
        typed = now - typedAt > 700 ? key : typed + key;
        typedAt = now;
        const all = options();
        const start = typed.length === 1 ? active + 1 : Math.max(active, 0);
        for (let n = 0; n < all.length; n++) {
            const i = (start + n) % all.length;
            if (!all[i].disabled && all[i].text.trim().toLowerCase().startsWith(typed.toLowerCase())) return i;
        }
        return -1;
    }

    button.addEventListener("keydown", (e) => {
        if (disabled()) return;
        const last = options().length - 1;
        if (!isOpen()) {
            if (["ArrowDown", "ArrowUp", "Enter", " "].includes(e.key)) open();
            else if (e.key === "Home") open(move(-1, 1));
            else if (e.key === "End") open(move(last + 1, -1));
            else if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
                const i = typeAhead(e.key);
                open(i >= 0 ? i : undefined);
            } else return;
            e.preventDefault();
            return;
        }
        switch (e.key) {
            case "ArrowDown":
                highlight(move(active, 1));
                break;
            case "ArrowUp":
                if (e.altKey) choose(active);
                else highlight(move(active, -1));
                break;
            case "Home":
                highlight(move(-1, 1));
                break;
            case "End":
                highlight(move(last + 1, -1));
                break;
            case "PageDown":
                highlight(move(active, PAGE));
                break;
            case "PageUp":
                highlight(move(active, -PAGE));
                break;
            case "Enter":
            case " ":
                if (e.key === " " && typed && Date.now() - typedAt < 700) {
                    const i = typeAhead(" ");
                    if (i >= 0) highlight(i);
                    break;
                }
                choose(active);
                break;
            case "Escape":
                close();
                break;
            case "Tab":
                choose(active);
                return; // Tab still moves the focus on.
            default:
                if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
                    const i = typeAhead(e.key);
                    if (i >= 0) highlight(i);
                    break;
                }
                return;
        }
        e.preventDefault();
    });
    button.addEventListener("click", () => (isOpen() ? close() : open()));
    // Pressing in the list keeps the focus on the button, so the list doesn't close under the pointer.
    list.addEventListener("pointerdown", (e) => e.preventDefault());
    list.addEventListener("click", (e) => {
        const row = e.target.closest(".tselect-option");
        if (row) choose(Number(row.dataset.index));
        button.focus();
    });
    list.addEventListener("pointermove", (e) => {
        const row = e.target.closest(".tselect-option");
        if (row && Number(row.dataset.index) !== active) highlight(Number(row.dataset.index), false);
    });
    button.addEventListener("blur", close);
    document.addEventListener("pointerdown", (e) => {
        if (!wrap.contains(e.target)) close();
    });
    window.addEventListener("resize", close);

    // Follow the select: new options, a changed label or disabled, a value set by the page's own code.
    new MutationObserver(render).observe(select, { childList: true, subtree: true, attributes: true, characterData: true });
    select.addEventListener("change", render);
    select.form?.addEventListener("reset", () => setTimeout(render));
    const proto = HTMLSelectElement.prototype;
    for (const key of ["value", "selectedIndex"]) {
        const native = Object.getOwnPropertyDescriptor(proto, key);
        Object.defineProperty(select, key, {
            configurable: true,
            get() {
                return native.get.call(this);
            },
            set(v) {
                native.set.call(this, v);
                render();
            },
        });
    }
    render();
    return wrap;
}

function svg(path, className) {
    const ns = "http://www.w3.org/2000/svg";
    const icon = document.createElementNS(ns, "svg");
    icon.setAttribute("viewBox", "0 0 24 24");
    icon.setAttribute("aria-hidden", "true");
    icon.setAttribute("class", className);
    const p = document.createElementNS(ns, "path");
    p.setAttribute("d", path);
    icon.append(p);
    return icon;
}
const chevron = () => svg("M7 10l5 5 5-5", "tselect-chevron");
const tick = () => svg("M5 12.5l4.5 4.5L19 7.5", "tselect-tick");
