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
    // Channels and guide is always on so phone/TV watch-first admin can rename channels and lists on the website.
    // Watch and Check my provider stay behind web_player (preview deployments and localhost always).
    ["channels", "Channels and guide", { href: "/account/channels" }],
    ["watch", "Watch", { href: "/watch", flag: "web_player" }],
    ["check", "Check my provider", { href: "/check", flag: "web_player" }],
    ["pair", "Pair a TV"],
    ["backup", "Backup"],
    ["features", "What Tuned does"],
    ["account", "Emails, sign out or delete"],
];

/**
 * Draws the menu into `nav`. `page` is "account", "playlists", "channels", "watch" or "check"; on the playlists page its
 * own entry is the page and `subsections` ([id, label]) list what's on it.
 */
export function drawNav(nav, page, subsections = []) {
    const list = document.createElement("ul");
    for (const [id, label, opts] of SECTIONS) {
        const item = document.createElement("li");
        const link = document.createElement("a");
        link.textContent = label;
        if (opts?.href) {
            link.href = opts.href;
            if (page === id) link.setAttribute("aria-current", "page");
            if (opts.flag) {
                item.hidden = true;
                flagOn(opts.flag).then((on) => (item.hidden = !on));
            }
        } else if (id === "playlists") {
            link.href = "/account/playlists";
            if (page === "playlists") link.setAttribute("aria-current", "page");
        } else link.href = page === "account" ? `#${id}` : `/account#${id}`;
        if (page === "account" && id !== "playlists" && !opts) link.dataset.section = id;
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

const PREVIEW = /^(localhost|127\.0\.0\.1)$|-projects-[a-z0-9]+\.vercel\.app$/;
let flags = null;

/** Whether an admin flag is on for this browser (check/verdict.mjs isPreviewHost: previews always). Off when unsure. */
export function flagOn(key) {
    if (PREVIEW.test(location.hostname)) return Promise.resolve(true);
    flags ??= fetch("/api/flags", { cache: "no-store" })
        .then((r) => r.json())
        .then((j) => j?.flags ?? {})
        .catch(() => ({}));
    return flags.then((f) => f[key] === true);
}

let observer = null;
let stopWatch = null;

/**
 * Marks the link of the section nearest the top of the window, as a reader scrolls.
 * When two cards share a row (same top), the first in page order used to always win — a click or
 * #hash now picks that card and keeps it while its row is in view; scrolling alone still marks
 * the first in a shared row.
 */
function watch(nav) {
    stopWatch?.();
    stopWatch = null;
    observer?.disconnect();
    observer = null;

    const links = [...nav.querySelectorAll("a[data-section]")];
    const sections = links.map((l) => document.getElementById(l.dataset.section)).filter(Boolean);
    if (!sections.length) return;

    const visible = new Set();
    let picked = hashSectionId(links);

    const apply = (id) => {
        for (const link of links) {
            if (id && link.dataset.section === id) link.setAttribute("aria-current", "location");
            else link.removeAttribute("aria-current");
        }
    };

    const mark = () => {
        const candidates = sections.filter((s) => visible.has(s) && !s.hidden);
        if (!candidates.length) return;
        // The topmost row in the spy band; tops within 32px count as side-by-side.
        let topY = Infinity;
        const tops = new Map();
        for (const s of candidates) {
            const y = s.getBoundingClientRect().top;
            tops.set(s, y);
            if (y < topY) topY = y;
        }
        const row = candidates.filter((s) => tops.get(s) - topY <= 32);
        const hash = hashSectionId(links);
        // Keep a click/#hash choice while that card shares the top row; never overwrite it from scroll.
        const active = row.find((s) => s.id === picked) ?? row.find((s) => s.id === hash) ?? row[0];
        apply(active.id);
    };

    const onClick = (event) => {
        const link = event.target.closest("a[data-section]");
        if (!link || !nav.contains(link)) return;
        picked = link.dataset.section;
        apply(picked);
    };

    const onHash = () => {
        const id = hashSectionId(links);
        if (!id) return;
        picked = id;
        apply(id);
    };

    nav.addEventListener("click", onClick);
    window.addEventListener("hashchange", onHash);

    if ("IntersectionObserver" in window) {
        observer = new IntersectionObserver(
            (entries) => {
                for (const e of entries) e.isIntersecting ? visible.add(e.target) : visible.delete(e.target);
                mark();
            },
            { rootMargin: "-96px 0px -55% 0px" },
        );
        sections.forEach((s) => observer.observe(s));
    }

    onHash();

    stopWatch = () => {
        nav.removeEventListener("click", onClick);
        window.removeEventListener("hashchange", onHash);
        observer?.disconnect();
        observer = null;
    };
}

function hashSectionId(links) {
    const id = decodeURIComponent(location.hash.slice(1));
    return id && links.some((l) => l.dataset.section === id) ? id : null;
}

/** A page opened at /account#devices draws its cards after loading, so it scrolls there once they exist. */
export function showLinkedSection() {
    const id = decodeURIComponent(location.hash.slice(1));
    if (!id) return;
    const target = document.getElementById(id);
    if (target && !target.hidden) target.scrollIntoView({ block: "start" });
}
