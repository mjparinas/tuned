// The account's side menu (docs/decisions/website.md › The account on a desktop; Melvin, 2026-11-20: "there's a lot more
// screen real estate than a phone. can you make it more intuitive and easier to use"). One list for both account pages,
// so they never disagree: on a desktop it sits on the left and stays put while the page scrolls; on a phone it is a
// row of chips under the title. The section in view is marked, and a link from the other page lands on its section
// once the page has drawn it.

const SECTIONS = [
    ["overview", "Overview"],
    ["devices", "Devices"],
    ["premium", "Premium"],
    ["playlists", "Playlists and settings"],
    ["pair", "Pair a TV"],
    ["backup", "Backup"],
    ["features", "What Tuned does"],
    ["account", "Sign out or delete"],
];

/**
 * Draws the menu into `nav`. `page` is "account" or "playlists"; on the playlists page its own entry is the page and
 * `subsections` ([id, label]) list what's on it.
 */
export function drawNav(nav, page, subsections = []) {
    const list = document.createElement("ul");
    for (const [id, label] of SECTIONS) {
        const item = document.createElement("li");
        const link = document.createElement("a");
        link.textContent = label;
        if (id === "playlists") {
            link.href = "/account/playlists";
            if (page === "playlists") link.setAttribute("aria-current", "page");
        } else link.href = page === "account" ? `#${id}` : `/account#${id}`;
        if (page === "account" && id !== "playlists") link.dataset.section = id;
        item.append(link);
        if (id === "playlists" && page === "playlists" && subsections.length) {
            const sub = document.createElement("ul");
            for (const [subId, subLabel] of subsections) {
                const subItem = document.createElement("li");
                const subLink = document.createElement("a");
                subLink.href = `#${subId}`;
                subLink.textContent = subLabel;
                subLink.dataset.section = subId;
                subItem.append(subLink);
                sub.append(subItem);
            }
            item.append(sub);
        }
        list.append(item);
    }
    nav.replaceChildren(list);
    watch(nav);
}

let observer = null;

/** Marks the link of the section nearest the top of the window, as a reader scrolls. */
function watch(nav) {
    observer?.disconnect();
    const links = [...nav.querySelectorAll("a[data-section]")];
    const sections = links.map((l) => document.getElementById(l.dataset.section)).filter(Boolean);
    if (!sections.length || !("IntersectionObserver" in window)) return;
    const visible = new Set();
    const mark = () => {
        const first = sections.find((s) => visible.has(s) && !s.hidden);
        for (const link of links) {
            if (first && link.dataset.section === first.id) link.setAttribute("aria-current", "location");
            else link.removeAttribute("aria-current");
        }
    };
    observer = new IntersectionObserver(
        (entries) => {
            for (const e of entries) e.isIntersecting ? visible.add(e.target) : visible.delete(e.target);
            mark();
        },
        { rootMargin: "-96px 0px -55% 0px" },
    );
    sections.forEach((s) => observer.observe(s));
}

/** A page opened at /account#devices draws its cards after loading, so it scrolls there once they exist. */
export function showLinkedSection() {
    const id = decodeURIComponent(location.hash.slice(1));
    if (!id) return;
    const target = document.getElementById(id);
    if (target && !target.hidden) target.scrollIntoView({ block: "start" });
}
