// Tuned admin (docs/decisions/admin.md): one page, no framework. Each section has its own address (?tab=Users,
// &open=<id> for an account or install, &days=<period>), and every move goes in the browser's history.
import { watchSelects } from "/select.js";

// --- Small helpers ---

function h(tag, attrs = {}, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs ?? {})) {
        if (v == null || v === false) continue;
        if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
        else if (k === "class") el.className = v;
        else el.setAttribute(k, v === true ? "" : v);
    }
    for (const child of children.flat(Infinity))
        if (child != null && child !== false) el.append(child instanceof Node ? child : String(child));
    return el;
}

const ICONS = {
    reports: '<path d="M4 4h16v12H8l-4 4z"/><path d="M12 8v3M12 13.5v.01"/>',
    todo: '<path d="M9 6h11M9 12h11M9 18h11"/><path d="m3.5 6 1.5 1.5L7.5 5M3.5 12l1.5 1.5L7.5 11M3.5 18l1.5 1.5L7.5 17"/>',
    overview:
        '<rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/>',
    traffic:
        '<path d="M3 12a9 9 0 1 0 18 0 9 9 0 0 0-18 0z"/><path d="M3 12h18M12 3c2.5 2.7 3.8 5.7 3.8 9s-1.3 6.3-3.8 9c-2.5-2.7-3.8-5.7-3.8-9S9.5 5.7 12 3z"/>',
    acquisition: '<path d="M12 3v12M7 10l5 5 5-5"/><path d="M4 17v3h16v-3"/>',
    funnel: '<path d="M3 4h18l-7 8v6l-4 2v-8z"/>',
    devices:
        '<rect x="2" y="5" width="14" height="10" rx="1.5"/><path d="M6 19h6M9 15v4"/><rect x="18" y="8" width="4" height="11" rx="1"/>',
    revenue:
        '<path d="M12 3v18M16.5 7.5c-.8-1.3-2.4-2-4.5-2-2.6 0-4.5 1.3-4.5 3.2 0 4.6 9 2.4 9 6.9 0 1.9-1.9 3.4-4.5 3.4-2.3 0-4-.8-4.8-2.3"/>',
    codes: '<path d="M3 8a2 2 0 0 0 0 4v4h18v-4a2 2 0 0 1 0-4V4H3z"/><path d="M13 4v16" stroke-dasharray="2 3"/>',
    users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.8-3.6 3.4-5.5 6.5-5.5s5.7 1.9 6.5 5.5"/><path d="M16 4.5a3.5 3.5 0 0 1 0 7M18.5 14.8c1.5.8 2.6 2.5 3 5.2"/>',
    installs: '<rect x="6" y="2" width="12" height="20" rx="2.5"/><path d="M11 18h2"/>',
    flags: '<rect x="2" y="7" width="20" height="10" rx="5"/><circle cx="16" cy="12" r="3"/>',
    automations: '<path d="M13 2 4 14h7l-1 8 9-12h-7z"/>',
    system: '<rect x="3" y="3" width="18" height="7" rx="1.5"/><rect x="3" y="14" width="18" height="7" rx="1.5"/><path d="M7 6.5h.01M7 17.5h.01"/>',
    log: '<path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 12h7M9 16h7"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
    out: '<path d="M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10"/>',
    back: '<path d="M15 18l-6-6 6-6"/>',
};
/** Like replaceChildren, but skips null and false (replaceChildren would print them). */
function put(el, ...children) {
    el.replaceChildren(...children.flat(Infinity).filter((c) => c != null && c !== false));
}

function icon(name) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("class", "icon");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    svg.innerHTML = ICONS[name] ?? ""; // our own constant markup
    return svg;
}

const num = (n) => (n == null ? "–" : Number(n).toLocaleString("en-CA"));
const money = (cents) =>
    cents == null ? "–" : `US$${(cents / 100).toLocaleString("en-CA", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const pct = (n) => (n == null ? "–" : `${n}%`);
const regions = typeof Intl.DisplayNames === "function" ? new Intl.DisplayNames(["en-CA"], { type: "region" }) : null;
const languages = typeof Intl.DisplayNames === "function" ? new Intl.DisplayNames(["en-CA"], { type: "language" }) : null;
const country = (code) => {
    if (!code || code === "Unknown") return "Unknown";
    try {
        return regions?.of(code) ?? code;
    } catch {
        return code;
    }
};
const language = (code) => {
    if (!code || code === "Unknown") return "Unknown";
    try {
        return languages?.of(code) ?? code;
    } catch {
        return code;
    }
};
function ago(value) {
    if (!value) return "never";
    const ms = Date.now() - new Date(value).getTime();
    const m = Math.round(ms / 60000);
    if (m < 1) return "just now";
    if (m < 60) return `${m} min ago`;
    const hr = Math.round(m / 60);
    if (hr < 24) return `${hr} h ago`;
    const d = Math.round(hr / 24);
    if (d === 1) return "yesterday";
    if (d < 60) return `${d} days ago`;
    return new Date(value).toLocaleDateString("en-CA", { year: "numeric", month: "short", day: "numeric" });
}
const when = (value) => (value ? new Date(value).toLocaleString("en-CA", { dateStyle: "medium", timeStyle: "short" }) : "–");
const plural = (n, one, many) => `${num(n)} ${n === 1 ? one : many}`;
const PRODUCTS = {
    premium_yearly: "Premium yearly",
    premium_lifetime: "Premium for life",
    seat_once: "Extra seat",
    seat_yearly: "Extra seat, yearly",
};
const SOURCES = { play: "Google Play", web: "Website (Stripe)", grant: "Given away", github: "GitHub", sideload: "Sideloaded" };

function toast(text) {
    const t = h("div", { class: "toast", role: "status" }, text);
    document.body.append(t);
    setTimeout(() => t.remove(), 2600);
}

class SignedOut extends Error {}

async function api(path, { method = "GET", body } = {}) {
    const response = await fetch(`/api/admin/${path}`, {
        method,
        headers: body ? { "Content-Type": "application/json" } : {},
        body: body ? JSON.stringify(body) : undefined,
        credentials: "same-origin",
    });
    const data = await response.json().catch(() => ({}));
    if (response.status === 401 && path !== "session") throw new SignedOut();
    if (!response.ok) throw Object.assign(new Error(data.error ?? data.status ?? "failed"), { data, status: response.status });
    return data;
}

// --- Navigation ---

const TABS = [
    { name: "Reports", icon: "reports", group: "Needs you" },
    { name: "To do", icon: "todo", group: "Needs you" },
    { name: "Overview", icon: "overview", group: "Numbers", period: true },
    { name: "Traffic", icon: "traffic", group: "Numbers", period: true },
    { name: "Acquisition", icon: "acquisition", group: "Numbers", period: true },
    { name: "Funnel", icon: "funnel", group: "Numbers", period: true },
    { name: "Devices", icon: "devices", group: "Numbers", period: true },
    { name: "Revenue", icon: "revenue", group: "Money", period: true },
    { name: "Codes", icon: "codes", group: "Money" },
    { name: "Users", icon: "users", group: "People" },
    { name: "Installs", icon: "installs", group: "People" },
    { name: "Flags", icon: "flags", group: "Running it" },
    { name: "Automations", icon: "automations", group: "Running it" },
    { name: "System", icon: "system", group: "Running it" },
    { name: "Log", icon: "log", group: "Running it" },
];
const PERIODS = [
    [1, "24h"],
    [7, "7d"],
    [30, "30d"],
    [90, "90d"],
    [365, "1y"],
];

const state = { tab: "Overview", days: 30, open: null, me: null, counts: {} };

function readUrl() {
    const q = new URLSearchParams(location.search);
    const tab = TABS.find((t) => t.name.toLowerCase().replace(" ", "-") === (q.get("tab") ?? "").toLowerCase());
    state.tab = tab?.name ?? "Overview";
    state.days = PERIODS.some(([d]) => d === Number(q.get("days"))) ? Number(q.get("days")) : 30;
    state.open = q.get("open");
    state.q = q.get("q") ?? "";
}

function go(tab, { open = null, q = "", replace = false } = {}) {
    state.tab = tab;
    state.open = open;
    state.q = q;
    const params = new URLSearchParams();
    params.set("tab", tab.toLowerCase().replace(" ", "-"));
    if (TABS.find((t) => t.name === tab)?.period && state.days !== 30) params.set("days", state.days);
    if (open) params.set("open", open);
    if (q) params.set("q", q);
    history[replace ? "replaceState" : "pushState"](null, "", `/admin?${params}`);
    render();
}

window.addEventListener("popstate", () => {
    readUrl();
    render();
});

// --- Building blocks ---

function tile(label, value, { delta = null, deltaText = null, note = null } = {}) {
    const d =
        delta == null
            ? null
            : h(
                  "div",
                  { class: `delta ${delta > 0 ? "up" : delta < 0 ? "down" : ""}` },
                  `${delta > 0 ? "+" : ""}${delta}% ${deltaText ?? "on the period before"}`,
              );
    return h(
        "div",
        { class: "tile" },
        h("div", { class: "label" }, label),
        h("div", { class: "value" }, value),
        d,
        note ? h("div", { class: "delta" }, note) : null,
    );
}

function card(title, body, { wide = false, note = null, iconName = null } = {}) {
    return h(
        "section",
        { class: `card${wide ? " wide" : ""}` },
        h("h2", {}, iconName ? icon(iconName) : null, title, note ? h("span", { class: "note" }, note) : null),
        body,
    );
}

function bars(rows, { label = (r) => r.label, value = (r) => num(r.count), empty = "Nothing yet." } = {}) {
    if (!rows?.length) return h("div", { class: "empty" }, empty);
    const max = Math.max(...rows.map((r) => r.count), 1);
    return h(
        "div",
        { class: "bars" },
        rows.map((r) =>
            h(
                "div",
                { class: "bar" },
                h("i", { class: "fill", style: `width:${(r.count / max) * 100}%` }),
                h("span", {}, label(r)),
                h("b", {}, value(r)),
            ),
        ),
    );
}

function columns(series, { label = "a day" } = {}) {
    if (!series?.length) return h("div", { class: "empty" }, "Nothing yet.");
    const max = Math.max(...series.map((s) => s.count), 1);
    const w = 600;
    const hgt = 150;
    const gap = series.length > 60 ? 0.5 : 2;
    const bw = w / series.length - gap;
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", `0 0 ${w} ${hgt}`);
    svg.setAttribute("preserveAspectRatio", "none");
    svg.setAttribute("role", "img");
    const total = series.reduce((s, x) => s + x.count, 0);
    svg.setAttribute("aria-label", `${num(total)} in all, at most ${num(max)} ${label}`);
    series.forEach((s, i) => {
        const bh = Math.max((s.count / max) * (hgt - 6), s.count ? 2 : 0.5);
        const r = document.createElementNS(ns, "rect");
        r.setAttribute("x", i * (bw + gap));
        r.setAttribute("y", hgt - bh);
        r.setAttribute("width", Math.max(bw, 0.5));
        r.setAttribute("height", bh);
        r.setAttribute("rx", Math.min(3, bw / 2));
        r.setAttribute("fill", s.count ? "var(--violet)" : "var(--line)");
        const t = document.createElementNS(ns, "title");
        t.textContent = `${s.day}: ${num(s.count)}`;
        r.append(t);
        svg.append(r);
    });
    const fmt = (d) => new Date(`${d}T12:00:00Z`).toLocaleDateString("en-CA", { month: "short", day: "numeric" });
    return h(
        "div",
        { class: "chart" },
        svg,
        h("div", { class: "axis" }, h("span", {}, fmt(series[0].day)), h("span", {}, fmt(series.at(-1).day))),
    );
}

function funnelView(steps, drop) {
    return h(
        "div",
        { class: "funnel" },
        steps.map((s, i) =>
            h(
                "div",
                { class: "step" },
                h("span", {}, s.label),
                h(
                    "span",
                    { class: "muted", title: i ? `${pct(s.ofPrevious)} of the step before, ${pct(s.ofFirst)} of the first` : null },
                    h("b", {}, num(s.count)),
                    i ? ` · ${pct(s.ofPrevious)}` : "",
                ),
                h("div", { class: "track" }, h("i", { style: `width:${Math.max(s.ofFirst, s.count ? 1 : 0)}%` })),
            ),
        ),
        drop
            ? h(
                  "div",
                  { class: "callout" },
                  `Biggest drop: ${drop.lost}% stop between “${drop.from}” and “${drop.to}”. Work on this step first.`,
              )
            : null,
    );
}

function table(headers, rows, { empty = "Nothing yet." } = {}) {
    if (!rows.length) return h("div", { class: "empty" }, empty);
    return h(
        "table",
        { class: "cardify" },
        h(
            "thead",
            {},
            h(
                "tr",
                {},
                // A column of buttons has no visible heading; a screen reader still hears "Actions" (WCAG 1.3.1).
                headers.map((x) => h("th", { class: x.num ? "num" : null }, x.label || h("span", { class: "sr-only" }, "Actions"))),
            ),
        ),
        h(
            "tbody",
            {},
            rows.map((cells) =>
                h(
                    "tr",
                    {},
                    cells.map((c, i) => h("td", { "data-label": headers[i].label, class: headers[i].num ? "num" : null }, c)),
                ),
            ),
        ),
    );
}

function switchButton(on, label, onChange, { disabled = false } = {}) {
    return h("button", {
        class: "switch",
        role: "switch",
        "aria-checked": String(on),
        "aria-label": label,
        disabled,
        onclick: (e) => onChange(e.currentTarget.getAttribute("aria-checked") !== "true", e.currentTarget),
    });
}

const bones = (kind = "tab") => {
    const bone = (style) => h("div", { class: "bone", style });
    const tiles = h(
        "div",
        { class: "tiles" },
        [1, 2, 3, 4].map(() => h("div", { class: "tile" }, bone("height:12px;width:50%"), bone("height:28px;width:70%;margin-top:10px"))),
    );
    const cardBones = (n) =>
        h(
            "div",
            { class: "cards" },
            Array.from({ length: n }, () =>
                h("div", { class: "card" }, bone("height:14px;width:40%;margin-bottom:16px"), bone("height:120px")),
            ),
        );
    if (kind === "list")
        return h(
            "div",
            { class: "card" },
            [1, 2, 3, 4, 5].map(() => h("div", { class: "item" }, bone("height:40px;flex:1"))),
        );
    return h("div", {}, tiles, cardBones(4));
};

const isAdmin = () => state.me?.role === "admin";

async function change(path, body, done = "Saved.") {
    try {
        const result = await api(path, { method: "POST", body });
        toast(done);
        return result;
    } catch (error) {
        if (error instanceof SignedOut) return gate();
        toast(error.data?.error ?? (error.status === 403 ? "Only admins can change this." : "That didn't work. Try again."));
        return null;
    }
}

// --- Sections ---

const SECTIONS = {
    async Overview() {
        const d = await api(`overview?days=${state.days}`);
        state.counts["To do"] = d.todoCount;
        state.counts.Reports = d.openReports;
        drawCounts();
        // Focus Overview: Needs you owns the first viewport; KPIs and charts wait below.
        const needsRows = d.needsYou ?? [];
        const needs = h(
            "section",
            {
                class: "needs-band",
                "aria-label": "Needs you",
                "data-needs": String(needsRows.length),
            },
            h(
                "header",
                { class: "needs-head" },
                h("h2", {}, "Needs you"),
                needsRows.length
                    ? h("span", { class: "pill amber", "aria-live": "polite" }, plural(needsRows.length, "item", "items"))
                    : h("span", { class: "pill teal" }, "Clear"),
                d.todoCount > 5 ? h("span", { class: "faint" }, `${d.todoCount} to-dos in all`) : null,
            ),
            needsRows.length
                ? h(
                      "div",
                      { class: "list needs-list" },
                      needsRows.map((i, index) =>
                          h(
                              "div",
                              {
                                  class: `item needs-item lamp-${i.kind === "urgent" ? "red" : i.kind === "setup" ? "amber" : "violet"}`,
                                  style: `--cascade: ${index}`,
                              },
                              h("span", {
                                  class: `lamp ${i.kind === "urgent" ? "red" : i.kind === "setup" ? "amber" : "violet"}`,
                                  "aria-hidden": "true",
                              }),
                              h("div", { class: "body" }, h("div", { class: "title" }, i.title), h("div", { class: "faint" }, i.detail)),
                              h("button", { class: "btn small", onclick: () => go(i.tab) }, "Open"),
                          ),
                      ),
                  )
                : h(
                      "p",
                      { class: "needs-empty" },
                      "Nothing needs you right now. New reports and to-dos will land here first.",
                  ),
        );
        return h(
            "div",
            { class: "overview-focus" },
            needs,
            h(
                "div",
                { class: "tiles" },
                tile("New installs", num(d.installs.now), { delta: d.installs.change }),
                tile("Active today", num(d.active.dau), { note: `${num(d.active.wau)} this week · ${num(d.active.mau)} this month` }),
                tile("New accounts", num(d.accounts.now), { delta: d.accounts.change }),
                tile("Premium accounts", num(d.premium), { note: `${plural(d.seats, "seat", "seats")} in use` }),
                tile("Sales", money(d.revenue.gross), { note: `${money(d.revenue.net)} after fees` }),
                tile("Yearly plans a month", money(d.revenue.mrr), { note: "Monthly share of yearly plans" }),
            ),
            h(
                "div",
                { class: "cards" },
                card("Installs a day", columns(d.installsByDay), { wide: true, iconName: "installs" }),
                card("By build", bars(d.builds, { label: (r) => SOURCES[r.label] ?? r.label })),
            ),
        );
    },

    async Traffic() {
        const d = await api(`traffic?days=${state.days}`);
        const c = (title, rows, opts) => card(title, bars(rows, opts));
        return h(
            "div",
            {},
            h(
                "div",
                { class: "tiles" },
                tile("Visitors", num(d.visitors.now), { delta: d.visitors.change }),
                tile("Page views", num(d.views.now), { delta: d.views.change }),
            ),
            h(
                "div",
                { class: "cards" },
                card("Visitors a day", columns(d.byDay), { wide: true, iconName: "traffic", note: "Counted once a day, no cookies" }),
                c("Pages", d.pages),
                c("Where they came from", d.referrers),
                c("Campaign sources (utm_source)", d.sources, { empty: "No tagged links yet." }),
                c("Campaigns", d.campaigns, { empty: "No campaigns yet." }),
                c("Countries", d.countries, { label: (r) => country(r.label) }),
                c("Buttons pressed", d.clicks),
                c("Browsers", d.browsers),
                c("Systems", d.os),
                c("Devices", d.devices),
            ),
        );
    },

    async Acquisition() {
        const d = await api(`acquisition?days=${state.days}`);
        return h(
            "div",
            {},
            h(
                "div",
                { class: "cards" },
                card("Installs a day", columns(d.byDay), { wide: true, iconName: "acquisition" }),
                card("Sources", bars(d.sources, { label: (r) => SOURCES[r.label] ?? r.label }), {
                    note: "Play's install referrer, or where the copy came from",
                }),
                card("Campaigns", bars(d.campaigns, { empty: "No tagged Play links yet." })),
                card("Mediums", bars(d.mediums, { empty: "No tagged Play links yet." })),
                card("Builds", bars(d.builds, { label: (r) => SOURCES[r.label] ?? r.label })),
                card("Countries", bars(d.countries, { label: (r) => country(r.label) })),
                card("Languages", bars(d.languages, { label: (r) => language(r.label) })),
            ),
        );
    },

    async Funnel() {
        const build = new URLSearchParams(location.search).get("build") ?? "";
        const d = await api(`funnel?days=${state.days}${build ? `&build=${build}` : ""}`);
        const pick = h(
            "div",
            { class: "segmented", role: "group", "aria-label": "Build" },
            [
                ["", "Both builds"],
                ["play", "Google Play"],
                ["github", "GitHub"],
            ].map(([v, l]) =>
                h(
                    "button",
                    {
                        "aria-pressed": String(build === v),
                        onclick: () => {
                            const q = new URLSearchParams(location.search);
                            if (v) q.set("build", v);
                            else q.delete("build");
                            history.replaceState(null, "", `/admin?${q}`);
                            render();
                        },
                    },
                    l,
                ),
            ),
        );
        return h(
            "div",
            {},
            h("div", { class: "row", style: "margin-bottom:16px" }, pick),
            h(
                "div",
                { class: "cards" },
                card("Getting going", funnelView(d.gettingGoing, d.drops.gettingGoing), {
                    iconName: "funnel",
                    note: "Installs in the period",
                }),
                card("Paying", funnelView(d.paying, d.drops.paying), { iconName: "revenue" }),
                card("Accounts and backups", funnelView(d.account, d.drops.account), { iconName: "users" }),
                card("Website", funnelView(d.website, null), { iconName: "traffic" }),
            ),
        );
    },

    async Devices() {
        const d = await api(`devices?days=${state.days}`);
        const kinds = { phone: "Phones", tablet: "Tablets", tv: "TVs" };
        return h(
            "div",
            { class: "cards" },
            card("Phones, tablets and TVs", bars(d.kinds, { label: (r) => kinds[r.label] ?? r.label }), {
                iconName: "devices",
                note: "Installs used in the period",
            }),
            card("Android versions", bars(d.android)),
            card("App versions", bars(d.versions)),
            card("Builds", bars(d.builds, { label: (r) => SOURCES[r.label] ?? r.label })),
            card("Makers", bars(d.makers)),
            card("Models", bars(d.models)),
        );
    },

    async Revenue() {
        const d = await api(`revenue?days=${state.days}`);
        const costs = [...(d.costs ?? [])];
        const costRows = h("div", { class: "list" });
        const drawCosts = () => {
            put(
                costRows,
                ...costs.map((c, i) =>
                    h(
                        "div",
                        { class: "item" },
                        h(
                            "div",
                            { class: "body" },
                            h("div", { class: "title" }, c.name),
                            h("div", { class: "faint" }, `${money(c.cents)} a ${c.every}`),
                        ),
                        isAdmin()
                            ? h(
                                  "button",
                                  {
                                      class: "btn small danger",
                                      "aria-label": `Remove ${c.name}`,
                                      onclick: async () => {
                                          costs.splice(i, 1);
                                          if (await change("revenue", { costs })) render();
                                      },
                                  },
                                  "Remove",
                              )
                            : null,
                    ),
                ),
                costs.length
                    ? ""
                    : h("div", { class: "empty" }, "No running costs yet. Vercel Pro, Neon and the Play developer fee are worth adding."),
            );
        };
        drawCosts();
        const name = h("input", { class: "plain", placeholder: "Vercel Pro", "aria-label": "Cost name", style: "flex:2;min-width:120px" });
        const amount = h("input", {
            class: "plain",
            placeholder: "US$20.00",
            inputmode: "decimal",
            "aria-label": "Amount in US dollars",
            style: "flex:1;min-width:80px",
        });
        const every = h(
            "select",
            { class: "plain", "aria-label": "How often" },
            h("option", { value: "month" }, "a month"),
            h("option", { value: "year" }, "a year"),
        );
        const addCost = isAdmin()
            ? h(
                  "div",
                  { class: "row", style: "margin-top:12px" },
                  name,
                  amount,
                  every,
                  h(
                      "button",
                      {
                          class: "btn",
                          onclick: async () => {
                              const cents = Math.round(parseFloat(amount.value.replace(/[^0-9.]/g, "")) * 100);
                              if (!name.value.trim() || !Number.isFinite(cents)) return toast("Give the cost a name and an amount.");
                              costs.push({ name: name.value.trim(), cents, every: every.value });
                              if (await change("revenue", { costs })) render();
                          },
                      },
                      "Add",
                  ),
              )
            : null;
        const product = Object.entries(d.now.byProduct).map(([label, count]) => ({ label, count }));
        const source = Object.entries(d.now.bySource).map(([label, count]) => ({ label, count }));
        return h(
            "div",
            {},
            h(
                "div",
                { class: "tiles" },
                tile("Sales", money(d.now.gross), { delta: d.change, note: plural(d.now.count, "purchase", "purchases") }),
                tile("After store fees", money(d.now.net), { note: "Play 15%, Stripe 2.9% + 30¢" }),
                tile("Yearly plans a month", money(d.mrr)),
                tile("Refunds", money(d.now.refunds), { note: `${plural(d.refunds14, "refund", "refunds")} in 14 days` }),
                tile("Running costs", money(d.costMonthly), { note: "a month" }),
                tile("Profit a month", money(d.profitMonthly), { note: "Estimate from this period" }),
            ),
            h(
                "div",
                { class: "cards" },
                card("Purchases a day", columns(d.purchasesByDay), { wide: true, iconName: "revenue", note: "At list price, before tax" }),
                card("By product", bars(product, { label: (r) => PRODUCTS[r.label] ?? r.label, value: (r) => money(r.count) })),
                card("By store", bars(source, { label: (r) => SOURCES[r.label] ?? r.label, value: (r) => money(r.count) })),
                card(
                    "Active now",
                    bars([
                        { label: "Premium", count: d.active.premium },
                        { label: "Extra seats", count: d.active.seats },
                        { label: "Given away with codes or by hand", count: d.active.grants },
                    ]),
                ),
                card("Running costs", h("div", {}, costRows, addCost)),
            ),
        );
    },

    async Codes() {
        const d = await api("codes");
        const form = h(
            "form",
            {
                class: "form",
                onsubmit: async (e) => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    const result = await change(
                        "codes",
                        {
                            do: "create",
                            code: f.get("code") || undefined,
                            kind: f.get("kind"),
                            quantity: Number(f.get("quantity") || 1),
                            maxUses: Number(f.get("maxUses") || 1),
                            days: f.get("days") ? Number(f.get("days")) : undefined,
                            note: f.get("note"),
                        },
                        "Code made.",
                    );
                    if (result) {
                        await navigator.clipboard?.writeText(result.code).catch(() => {});
                        render();
                    }
                },
            },
            h(
                "label",
                { class: "field" },
                "Gives",
                h(
                    "select",
                    { name: "kind" },
                    h("option", { value: "premium" }, "Premium for life"),
                    h("option", { value: "seats" }, "Extra seats"),
                ),
            ),
            h(
                "label",
                { class: "field" },
                "Seats (for seats)",
                h("input", { name: "quantity", type: "number", min: 1, max: 10, value: 1 }),
            ),
            h("label", { class: "field" }, "Uses", h("input", { name: "maxUses", type: "number", min: 1, max: 10000, value: 1 })),
            h("label", { class: "field" }, "Lasts (days, blank for ever)", h("input", { name: "days", type: "number", min: 1, max: 3650 })),
            h(
                "label",
                { class: "field" },
                "Code (blank for random)",
                h("input", { name: "code", placeholder: "TUNED-…", autocapitalize: "characters" }),
            ),
            h("label", { class: "field" }, "Note (only you see it)", h("input", { name: "note", placeholder: "For Sam" })),
            h("button", { class: "btn primary", type: "submit" }, "Make code"),
        );
        const status = (c) =>
            c.disabled
                ? h("span", { class: "pill grey" }, "Off")
                : c.expires_at && new Date(c.expires_at) < new Date()
                  ? h("span", { class: "pill amber" }, "Expired")
                  : c.uses >= c.max_uses
                    ? h("span", { class: "pill grey" }, "Used up")
                    : h("span", { class: "pill green" }, "Live");
        return h(
            "div",
            { class: "cards" },
            isAdmin()
                ? card(
                      "Make a code",
                      h(
                          "div",
                          {},
                          h(
                              "p",
                              { class: "faint", style: "margin-top:0" },
                              "Redeemed on the account page while signed in. Seats follow the account's Premium: lifetime seats for lifetime, yearly for yearly.",
                          ),
                          form,
                      ),
                      { wide: true, iconName: "codes" },
                  )
                : null,
            card(
                "Codes",
                table(
                    [
                        { label: "Code" },
                        { label: "Gives" },
                        { label: "Used", num: true },
                        { label: "Ends" },
                        { label: "Note" },
                        { label: "State" },
                        { label: "" },
                    ],
                    d.codes.map((c) => [
                        h("code", {}, c.code),
                        c.kind === "seats" ? plural(c.quantity, "seat", "seats") : "Premium for life",
                        `${num(c.uses)} of ${num(c.max_uses)}`,
                        c.expires_at ? new Date(c.expires_at).toLocaleDateString("en-CA") : "Never",
                        c.note || h("span", { class: "faint" }, "–"),
                        status(c),
                        isAdmin()
                            ? h(
                                  "button",
                                  {
                                      class: "btn small",
                                      onclick: async () =>
                                          (await change("codes", { do: c.disabled ? "enable" : "disable", code: c.code })) && render(),
                                  },
                                  c.disabled ? "Turn on" : "Turn off",
                              )
                            : "",
                    ]),
                    { empty: "No codes yet." },
                ),
                { wide: true },
            ),
        );
    },

    async Users() {
        if (state.open) return userPage(state.open);
        const d = await api(`users?q=${encodeURIComponent(state.q)}`);
        const input = h("input", {
            class: "plain",
            type: "search",
            value: state.q,
            placeholder: "Part of an email, or an account id",
            "aria-label": "Search accounts",
            style: "flex:1;min-width:220px",
        });
        const search = h(
            "form",
            {
                class: "row",
                style: "margin-bottom:16px",
                onsubmit: (e) => (e.preventDefault(), go("Users", { q: input.value.trim(), replace: true })),
            },
            input,
            h("button", { class: "btn", type: "submit" }, "Search"),
        );
        return h(
            "div",
            {},
            h(
                "div",
                { class: "tiles" },
                tile("Accounts", num(d.totals.total)),
                tile("New in 30 days", num(d.totals.new30)),
                tile("Used in 30 days", num(d.totals.seen30)),
                tile("With Premium", num(d.totals.premium)),
            ),
            search,
            card(
                state.q ? `Accounts matching “${state.q}”` : "Newest accounts",
                table(
                    [
                        { label: "Email" },
                        { label: "Joined" },
                        { label: "Last used" },
                        { label: "Devices", num: true },
                        { label: "Owns" },
                        { label: "" },
                    ],
                    d.users.map((u) => [
                        h("div", {}, u.email, u.name ? h("div", { class: "faint" }, u.name) : null),
                        ago(u.created_at),
                        ago(u.last_seen),
                        num(u.devices),
                        u.products.length
                            ? h(
                                  "div",
                                  { class: "row" },
                                  u.products.map((p) => h("span", { class: "pill" }, PRODUCTS[p] ?? p)),
                              )
                            : h("span", { class: "faint" }, "Free"),
                        h("button", { class: "btn small", onclick: () => go("Users", { open: u.id, q: state.q }) }, "Open"),
                    ]),
                    { empty: "No accounts match." },
                ),
                { wide: true, iconName: "users" },
            ),
        );
    },

    async Installs() {
        if (state.open) return installPage(state.open);
        const q = new URLSearchParams(location.search);
        const filters = { q: q.get("q") ?? "", build: q.get("build") ?? "", country: q.get("country") ?? "", tests: q.get("tests") ?? "" };
        const query = new URLSearchParams(Object.entries(filters).filter(([, v]) => v));
        const d = await api(`installs?${query}`);
        const rows = [...d.installs];
        let next = d.next;
        const setFilter = (k, v) => {
            const p = new URLSearchParams(location.search);
            if (v) p.set(k, v);
            else p.delete(k);
            history.replaceState(null, "", `/admin?${p}`);
            render();
        };
        const search = h("input", {
            class: "plain",
            type: "search",
            value: filters.q,
            placeholder: "Id, model, source or campaign",
            "aria-label": "Search installs",
            style: "flex:1;min-width:200px",
        });
        const bar = h(
            "form",
            { class: "row", style: "margin-bottom:16px", onsubmit: (e) => (e.preventDefault(), setFilter("q", search.value.trim())) },
            search,
            h(
                "select",
                { class: "plain", "aria-label": "Build", onchange: (e) => setFilter("build", e.target.value) },
                h("option", { value: "" }, "Both builds"),
                h("option", { value: "play", selected: filters.build === "play" }, "Google Play"),
                h("option", { value: "github", selected: filters.build === "github" }, "GitHub"),
            ),
            h("input", {
                class: "plain",
                value: filters.country,
                placeholder: "Country (CA)",
                maxlength: 2,
                size: 8,
                "aria-label": "Country code",
                onchange: (e) => setFilter("country", e.target.value.trim()),
            }),
            h(
                "label",
                { class: "row faint" },
                h("input", {
                    type: "checkbox",
                    checked: filters.tests === "1",
                    onchange: (e) => setFilter("tests", e.target.checked ? "1" : ""),
                }),
                "Show test installs",
            ),
        );
        const body = h("div");
        const draw = () => {
            put(
                body,
                table(
                    [
                        { label: "Installed" },
                        { label: "Device" },
                        { label: "Version" },
                        { label: "Where from" },
                        { label: "Country" },
                        { label: "Played" },
                        { label: "" },
                    ],
                    rows.map((i) => [
                        ago(i.first_seen),
                        h(
                            "div",
                            {},
                            `${i.maker ?? ""} ${i.model ?? ""}`.trim() || "Unknown",
                            h("div", { class: "faint" }, `${i.kind ?? "?"} · Android ${i.android ?? "?"}`),
                        ),
                        h("div", {}, i.version ?? "–", h("div", { class: "faint" }, SOURCES[i.build] ?? i.build ?? "")),
                        i.utm_campaign ? `${i.source ?? "?"} · ${i.utm_campaign}` : (SOURCES[i.source] ?? i.source ?? "–"),
                        country(i.country),
                        i.played ? h("span", { class: "pill green" }, "Yes") : h("span", { class: "pill grey" }, "Not yet"),
                        h(
                            "div",
                            { class: "row" },
                            i.test ? h("span", { class: "pill amber" }, "Test") : null,
                            h("button", { class: "btn small", onclick: () => go("Installs", { open: i.id }) }, "Open"),
                        ),
                    ]),
                    { empty: "No installs yet. They appear once the app sends its first usage stats." },
                ),
                next
                    ? h(
                          "div",
                          { class: "row", style: "justify-content:center;margin-top:12px" },
                          h(
                              "button",
                              {
                                  class: "btn",
                                  onclick: async () => {
                                      query.set("before", next);
                                      const more = await api(`installs?${query}`);
                                      rows.push(...more.installs);
                                      next = more.next;
                                      draw();
                                  },
                              },
                              "Load more",
                          ),
                      )
                    : null,
            );
        };
        draw();
        return h(
            "div",
            {},
            bar,
            card("Latest installs", body, {
                wide: true,
                iconName: "installs",
                note: "The owner's and CI's devices are marked as tests and left out of every number",
            }),
        );
    },

    async Reports() {
        const status = new URLSearchParams(location.search).get("status") ?? "open";
        const d = await api(`reports?status=${status}`);
        state.counts.Reports = d.counts.open ?? 0;
        drawCounts();
        const pick = h(
            "div",
            { class: "segmented", role: "group", "aria-label": "Which reports" },
            [
                ["open", `To read (${num(d.counts.open ?? 0)})`],
                ["done", `Done (${num(d.counts.done ?? 0)})`],
                ["dismissed", `Dismissed (${num(d.counts.dismissed ?? 0)})`],
                ["all", "Everything"],
            ].map(([v, l]) =>
                h(
                    "button",
                    {
                        "aria-pressed": String(status === v),
                        onclick: () => {
                            const q = new URLSearchParams(location.search);
                            q.set("status", v);
                            history.replaceState(null, "", `/admin?${q}`);
                            render();
                        },
                    },
                    l,
                ),
            ),
        );
        const report = (r) => {
            const note = h("input", {
                class: "plain",
                value: r.note ?? "",
                placeholder: "Note for yourself",
                "aria-label": "Note",
                style: "flex:1;min-width:180px",
            });
            const act = (s) => async () =>
                (await change(
                    "reports",
                    { id: r.id, status: s, note: note.value },
                    s === "done" ? "Marked done." : s === "dismissed" ? "Dismissed." : "Back to read.",
                )) && render();
            // The Report button on Logs puts the device's details above the log (lib/admin/analytics.mjs, storedLog).
            const split = (r.log ?? "").startsWith("==== Device ====\n") ? r.log.indexOf("\n==== Log ====\n") : -1;
            const details = split > 0 ? r.log.slice("==== Device ====\n".length, split) : "";
            const log = split > 0 ? r.log.slice(split + "\n==== Log ====\n".length) : r.log;
            return h(
                "div",
                { class: "card" },
                h(
                    "h2",
                    {},
                    h("span", { class: `dot ${r.crash ? "red" : "violet"}`, style: "margin-top:0" }),
                    details && !r.what ? "Logs sent from the app" : r.crash ? "Crash or freeze, with words" : "Problem report",
                    h(
                        "span",
                        { class: "note" },
                        `${ago(r.at)} · ${[r.model, r.android ? `Android ${r.android}` : null, r.version, SOURCES[r.build] ?? r.build].filter(Boolean).join(" · ")}`,
                    ),
                ),
                h(
                    "p",
                    { style: "margin:0 0 8px;white-space:pre-wrap" },
                    r.what ||
                        h(
                            "span",
                            { class: "faint" },
                            details ? "Sent from Logs with one button, so no words." : "No words, only the crash.",
                        ),
                ),
                details
                    ? Object.assign(h("details", {}, h("summary", {}, "Device and install"), h("pre", {}, details)), { open: true })
                    : null,
                r.contact ? h("p", { class: "faint", style: "margin:0 0 8px" }, `Reply to: ${r.contact}`) : null,
                r.crash ? h("details", {}, h("summary", {}, "Crash or freeze report"), h("pre", {}, r.crash)) : null,
                log ? h("details", {}, h("summary", {}, "Log (logins already removed by the app)"), h("pre", {}, log)) : null,
                h(
                    "div",
                    { class: "row", style: "margin-top:12px" },
                    r.install_id
                        ? h("button", { class: "btn small", onclick: () => go("Installs", { open: r.install_id }) }, "Open install")
                        : null,
                    isAdmin() ? note : null,
                    isAdmin() && r.status !== "done" ? h("button", { class: "btn small primary", onclick: act("done") }, "Done") : null,
                    isAdmin() && r.status !== "dismissed"
                        ? h("button", { class: "btn small", onclick: act("dismissed") }, "Dismiss")
                        : null,
                    isAdmin() && r.status !== "open" ? h("button", { class: "btn small", onclick: act("open") }, "Read again") : null,
                    r.handled_by
                        ? h(
                              "span",
                              { class: "faint" },
                              `${r.status === "done" ? "Done" : "Handled"} by ${r.handled_by}, ${ago(r.handled_at)}`,
                          )
                        : null,
                ),
            );
        };
        return h(
            "div",
            {},
            h("div", { class: "row", style: "margin-bottom:16px" }, pick),
            d.reports.length
                ? h("div", { class: "grid" }, d.reports.map(report))
                : h(
                      "div",
                      { class: "card" },
                      h(
                          "div",
                          { class: "empty" },
                          status === "open"
                              ? "Nothing to read. Reports from Settings › Report a problem and the Report button on Logs land here."
                              : "Nothing here.",
                      ),
                  ),
        );
    },

    async "To do"() {
        const d = await api("todo");
        state.counts["To do"] = d.items.length;
        drawCounts();
        const snooze = (i, until) => async () =>
            (await change("todo", { do: until ? "snooze" : "dismiss", key: i.key, until }, until ? "Snoozed." : "Dismissed.")) && render();
        const items = d.items.map((i) =>
            h(
                "div",
                { class: "item" },
                h("span", { class: `dot ${i.kind === "urgent" ? "red" : i.kind === "setup" ? "amber" : "violet"}` }),
                h("div", { class: "body" }, h("div", { class: "title" }, i.title), h("div", { class: "faint" }, i.detail)),
                h(
                    "div",
                    { class: "actions" },
                    h("button", { class: "btn small", onclick: () => go(i.tab) }, "Open"),
                    isAdmin() ? h("button", { class: "btn small", onclick: snooze(i, "tomorrow") }, "Tomorrow") : null,
                    isAdmin() ? h("button", { class: "btn small", onclick: snooze(i, "week") }, "Next week") : null,
                    isAdmin() ? h("button", { class: "btn small", onclick: snooze(i, null) }, "Dismiss") : null,
                ),
            ),
        );
        const title = h("input", { class: "plain", placeholder: "Something to do", "aria-label": "Task", style: "flex:2;min-width:200px" });
        const due = h("input", { class: "plain", type: "date", "aria-label": "Due" });
        const add = h(
            "form",
            {
                class: "row",
                style: "margin-bottom:8px",
                onsubmit: async (e) => {
                    e.preventDefault();
                    if (!title.value.trim()) return;
                    if (await change("todo", { do: "add", title: title.value, due: due.value }, "Added.")) render();
                },
            },
            title,
            due,
            h("button", { class: "btn primary", type: "submit" }, "Add"),
        );
        const tasks = d.tasks.map((t) =>
            h(
                "div",
                { class: "item" },
                h("input", {
                    type: "checkbox",
                    checked: Boolean(t.done_at),
                    disabled: !isAdmin(),
                    "aria-label": `Done: ${t.title}`,
                    style: "width:18px;height:18px;margin-top:3px",
                    onchange: async (e) =>
                        (await change(
                            "todo",
                            { do: "done", id: t.id, done: e.target.checked },
                            e.target.checked ? "Done." : "Back on the list.",
                        )) && render(),
                }),
                h(
                    "div",
                    { class: "body" },
                    h("div", { class: "title", style: t.done_at ? "text-decoration:line-through;opacity:.6" : null }, t.title),
                    h(
                        "div",
                        { class: "faint" },
                        [
                            t.due ? `Due ${new Date(t.due).toLocaleDateString("en-CA", { timeZone: "UTC" })}` : null,
                            `Added by ${t.created_by}`,
                        ]
                            .filter(Boolean)
                            .join(" · "),
                    ),
                ),
            ),
        );
        return h(
            "div",
            { class: "cards" },
            card(
                "Worked out from the data",
                items.length ? h("div", { class: "list" }, items) : h("div", { class: "empty" }, "All clear."),
                { wide: true, iconName: "todo" },
            ),
            card(
                "Your own tasks",
                h(
                    "div",
                    {},
                    isAdmin() ? add : null,
                    tasks.length ? h("div", { class: "list" }, tasks) : h("div", { class: "empty" }, "No tasks."),
                ),
                { wide: true },
            ),
        );
    },

    async Flags() {
        const d = await api("flags");
        const groups = [...new Set(d.flags.map((f) => f.group))];
        const row = (f) => {
            const current = f.changed ? f.changed.value : f.default;
            const share = h("input", {
                class: "plain",
                type: "number",
                min: 0,
                max: 100,
                value: f.changed?.share ?? 100,
                size: 4,
                "aria-label": `Share of installs for ${f.label}`,
                disabled: !isAdmin(),
                style: "width:76px",
            });
            const value =
                f.type === "bool"
                    ? h(
                          "select",
                          { class: "plain", "aria-label": f.label, disabled: !isAdmin() },
                          h("option", { value: "true", selected: current === true }, "On"),
                          h("option", { value: "false", selected: current === false }, "Off"),
                      )
                    : h("input", {
                          class: "plain",
                          type: "number",
                          min: f.min,
                          max: f.max,
                          value: current,
                          "aria-label": f.label,
                          disabled: !isAdmin(),
                          style: "width:120px",
                      });
            const save = h(
                "button",
                {
                    class: "btn small primary",
                    hidden: true,
                    onclick: async () => {
                        const v = f.type === "bool" ? value.value === "true" : Number(value.value);
                        if (await change("flags", { key: f.key, value: v, share: Number(share.value) })) render();
                    },
                },
                "Save",
            );
            const show = () => (save.hidden = false);
            value.addEventListener("input", show);
            share.addEventListener("input", show);
            return h(
                "div",
                { class: "item" },
                h(
                    "div",
                    { class: "body" },
                    h("div", { class: "title" }, f.label, " ", f.changed ? h("span", { class: "pill teal" }, "Changed") : null),
                    h(
                        "div",
                        { class: "faint" },
                        h("code", {}, f.key),
                        ` · default ${f.type === "bool" ? (f.default ? "on" : "off") : f.default}`,
                        f.changed ? ` · set by ${f.changed.updated_by}, ${ago(f.changed.updated_at)}` : "",
                    ),
                ),
                h(
                    "div",
                    { class: "actions" },
                    value,
                    h("span", { class: "row faint" }, "for", share, "% of installs"),
                    save,
                    f.changed && isAdmin()
                        ? h(
                              "button",
                              {
                                  class: "btn small",
                                  onclick: async () =>
                                      (await change("flags", { key: f.key, value: null }, "Back to the default.")) && render(),
                              },
                              "Default",
                          )
                        : null,
                ),
            );
        };
        const changed = d.flags.filter((f) => f.changed);
        return h(
            "div",
            { class: "cards" },
            changed.length
                ? card("Changed from the default", h("div", { class: "list" }, changed.map(row)), { wide: true, iconName: "flags" })
                : null,
            groups.map((g) =>
                card(g, h("div", { class: "list" }, d.flags.filter((f) => f.group === g && !f.changed).map(row)), { wide: true }),
            ),
            card(
                "How the app reads these",
                h(
                    "p",
                    { class: "faint", style: "margin:0" },
                    "The app asks GET /api/flags when it opens and keeps the last answer, so a flag takes effect the next time each device opens Tuned. A share rolls out to a fixed set of installs, and raising it only adds more.",
                ),
                { wide: true },
            ),
        );
    },

    async Automations() {
        const d = await api("automations");
        return h(
            "div",
            { class: "cards" },
            card(
                "Emails to admins",
                h(
                    "div",
                    {},
                    !d.mailReady
                        ? h("div", { class: "notice" }, "Set RESEND_API_KEY (and ADMIN_FROM, a verified address) on Vercel to send these.")
                        : null,
                    h(
                        "div",
                        { class: "list" },
                        d.automations.map((a) =>
                            h(
                                "div",
                                { class: "item" },
                                h("div", { class: "body" }, h("div", { class: "title" }, a.label), h("div", { class: "faint" }, a.detail)),
                                switchButton(
                                    a.on,
                                    a.label,
                                    async (on, el) => {
                                        if (await change("automations", { key: a.key, on }, on ? "On." : "Off."))
                                            el.setAttribute("aria-checked", String(on));
                                    },
                                    { disabled: !isAdmin() },
                                ),
                            ),
                        ),
                    ),
                    h(
                        "div",
                        { class: "row", style: "margin-top:12px" },
                        isAdmin()
                            ? h(
                                  "button",
                                  {
                                      class: "btn",
                                      onclick: async () => {
                                          const r = await change("automations", { do: "test" }, "Sending…");
                                          if (r) toast(r.sent ? "Test email sent." : "It didn't send. Check RESEND_API_KEY.");
                                      },
                                  },
                                  "Send a test email",
                              )
                            : null,
                        h(
                            "span",
                            { class: "faint" },
                            `${plural(d.recipients, "address", "addresses")} on ADMIN_EMAILS · each alert at most every 6 hours · last run ${ago(d.lastRun)}`,
                        ),
                    ),
                ),
                { wide: true, iconName: "automations" },
            ),
            card(
                "Scheduled jobs",
                table(
                    [{ label: "Job" }, { label: "When" }, { label: "What it does" }],
                    [
                        [
                            "Alerts and digest",
                            "Every hour at :05",
                            "Checks for purchases, refunds, reports and failed sign-ins, and sends the morning digest after 13:00 UTC.",
                        ],
                        [
                            "Clean-up",
                            "Daily, 04:05 UTC",
                            "Deletes app events and page views older than 25 months, as the privacy policy says.",
                        ],
                    ],
                ),
                { wide: true },
            ),
        );
    },

    async System() {
        const d = await api("system");
        const dot = (s) =>
            h(
                "span",
                { class: `pill ${s === "green" ? "green" : s === "amber" ? "amber" : "red"}` },
                s === "green" ? "Working" : s === "amber" ? "Needs a look" : "Not working",
            );
        const runText = (r) =>
            r
                ? h("span", {}, h("a", { href: r.url, target: "_blank", rel: "noopener" }, r.conclusion ?? r.status), ` · ${ago(r.at)}`)
                : "No runs";
        const mb = (b) => `${(b / 1024 / 1024).toFixed(1)} MB`;
        const settings = d.settings.list;
        return h(
            "div",
            { class: "cards" },
            card(
                "Database (Neon)",
                d.database.error
                    ? h("p", { class: "faint" }, d.database.error)
                    : h(
                          "div",
                          {},
                          h("div", { class: "value", style: "font-size:22px;font-weight:700" }, mb(d.database.bytes)),
                          h(
                              "div",
                              { class: "faint" },
                              `${Math.round((d.database.bytes / d.database.limitBytes) * 100)}% of Neon's free 0.5 GB · answered in ${d.database.ms} ms`,
                          ),
                      ),
                { iconName: "system", note: dot(d.database.state) },
            ),
            card("Redis (Upstash)", h("p", { class: "faint", style: "margin:0" }, d.redis.error ?? `Answered in ${d.redis.ms} ms`), {
                note: dot(d.redis.state),
            }),
            card(
                "CI",
                d.ci.error
                    ? h("p", { class: "faint" }, d.ci.error)
                    : h(
                          "div",
                          { class: "list" },
                          h("div", { class: "item" }, h("div", { class: "body" }, "staging"), runText(d.ci.staging)),
                          h("div", { class: "item" }, h("div", { class: "body" }, "main"), runText(d.ci.main)),
                      ),
                { note: dot(d.ci.state) },
            ),
            card(
                "Releases",
                d.releases.error
                    ? h("p", { class: "faint" }, d.releases.error)
                    : h(
                          "div",
                          {},
                          h("div", { class: "value", style: "font-size:22px;font-weight:700" }, d.releases.version ?? "None yet"),
                          h("div", { class: "faint" }, d.releases.publishedAt ? `Published ${ago(d.releases.publishedAt)}` : ""),
                          h("div", { class: "faint" }, "Last release run: ", runText(d.releases.last)),
                      ),
                { note: dot(d.releases.state) },
            ),
            card(
                "This deployment",
                h(
                    "div",
                    { class: "faint" },
                    `${d.deploy.env}${d.deploy.branch ? ` · ${d.deploy.branch}` : ""}${d.deploy.commit ? ` · ${d.deploy.commit}` : ""}${d.deploy.region ? ` · ${d.deploy.region}` : ""}`,
                ),
                { note: dot(d.deploy.state) },
            ),
            card(
                "Security",
                h(
                    "div",
                    { class: "faint" },
                    `${plural(d.security.failedToday, "wrong second-step code", "wrong second-step codes")} today · two-step sign-in ${d.security.twoFactor}`,
                ),
                { note: dot(d.security.state) },
            ),
            card(
                "Settings on Vercel",
                table(
                    [{ label: "Name" }, { label: "What it powers" }, { label: "State" }],
                    settings.map((s) => [
                        h(
                            "div",
                            {},
                            s.names.map((n, i) => [i ? h("br") : null, h("code", {}, n)]),
                        ),
                        h("span", {}, s.powers, s.required ? "" : h("span", { class: "faint" }, " (optional)")),
                        h(
                            "span",
                            { class: `pill ${s.state === "set" ? "green" : s.state === "partly set" || !s.required ? "amber" : "red"}` },
                            s.state === "set" ? "Set" : s.state === "partly set" ? "Partly set" : "Missing",
                        ),
                    ]),
                ),
                { wide: true, note: "Names only, never values" },
            ),
        );
    },

    async Log() {
        const q = new URLSearchParams(location.search);
        const filters = { who: q.get("who") ?? "", what: q.get("what") ?? "" };
        const base = new URLSearchParams(Object.entries(filters).filter(([, v]) => v));
        const d = await api(`log?${base}`);
        const entries = [...d.entries];
        let next = d.next;
        const set = (k, v) => {
            const p = new URLSearchParams(location.search);
            if (v) p.set(k, v);
            else p.delete(k);
            history.replaceState(null, "", `/admin?${p}`);
            render();
        };
        const body = h("div");
        const draw = () =>
            put(
                body,
                table(
                    [{ label: "When" }, { label: "Who" }, { label: "What" }, { label: "Details" }],
                    entries.map((e) => [
                        when(e.at),
                        e.actor,
                        h("code", {}, e.action),
                        h(
                            "span",
                            { class: "faint" },
                            Object.entries(e.detail ?? {})
                                .filter(([, v]) => v != null && v !== "")
                                .map(([k, v]) => `${k}: ${typeof v === "object" ? JSON.stringify(v) : v}`)
                                .join(" · "),
                        ),
                    ]),
                    { empty: "Nothing logged yet." },
                ),
                next
                    ? h(
                          "div",
                          { class: "row", style: "justify-content:center;margin-top:12px" },
                          h(
                              "button",
                              {
                                  class: "btn",
                                  onclick: async () => {
                                      base.set("before", next);
                                      const more = await api(`log?${base}`);
                                      entries.push(...more.entries);
                                      next = more.next;
                                      draw();
                                  },
                              },
                              "Load more",
                          ),
                      )
                    : null,
            );
        draw();
        const who = h("input", {
            class: "plain",
            value: filters.who,
            placeholder: "Who (an email)",
            "aria-label": "Who",
            onchange: (e) => set("who", e.target.value.trim()),
        });
        const what = h(
            "select",
            { class: "plain", "aria-label": "What", onchange: (e) => set("what", e.target.value) },
            [
                ["", "Everything"],
                ["signin", "Sign-ins"],
                ["2fa", "Two-step"],
                ["flag", "Flags"],
                ["code", "Codes"],
                ["user", "Accounts"],
                ["report", "Reports"],
                ["todo", "To do"],
                ["install", "Installs"],
                ["automation", "Automations"],
                ["costs", "Costs"],
            ].map(([v, l]) => h("option", { value: v, selected: filters.what === v }, l)),
        );
        return h(
            "div",
            {},
            h("div", { class: "row", style: "margin-bottom:16px" }, who, what),
            card("Every sign-in and change", body, { wide: true, iconName: "log", note: "Newest first" }),
        );
    },
};

async function userPage(id) {
    const d = await api(`users?id=${encodeURIComponent(id)}`);
    const a = d.account;
    const act = (body, done) => async () => (await change("users", { id: a.id, ...body }, done)) && render();
    const hasGrant = d.entitlements.some((e) => e.source === "grant" && e.status === "active" && e.product === "premium_lifetime");
    return h(
        "div",
        {},
        h("button", { class: "btn small back", onclick: () => go("Users", { q: state.q }) }, icon("back"), "All accounts"),
        h(
            "div",
            { class: "tiles" },
            tile("Account", h("span", { style: "font-size:17px" }, a.email), {
                note: `${a.name || "No name"} · joined ${ago(a.created_at)}`,
            }),
            tile("Premium", d.plan.premium === "lifetime" ? "For life" : d.plan.premium === "yearly" ? "Yearly" : "Free"),
            tile("Seats", d.plan.premium ? `${num(d.devices.filter((x) => x.seated).length)} of ${num(d.plan.limit)}` : "–", {
                note: d.plan.extra ? `${plural(d.plan.extra, "extra seat", "extra seats")}` : null,
            }),
            tile("Backups", num(d.backups.length), {
                note: d.backups[0] ? `Last ${ago(d.backups[0].saved_at)} from ${d.backups[0].device_name}` : "None yet",
            }),
        ),
        h(
            "div",
            { class: "cards" },
            card(
                "Devices",
                table(
                    [{ label: "Name" }, { label: "Kind" }, { label: "Build" }, { label: "Last used" }, { label: "Seat" }, { label: "" }],
                    d.devices.map((x) => [
                        x.name,
                        x.kind,
                        SOURCES[x.build] ?? x.build,
                        ago(x.last_seen),
                        x.seated ? h("span", { class: "pill green" }, "Has a seat") : h("span", { class: "pill grey" }, "No seat"),
                        x.seated && isAdmin()
                            ? h(
                                  "button",
                                  { class: "btn small", onclick: act({ do: "free-seat", device: x.id }, "Seat freed.") },
                                  "Free seat",
                              )
                            : "",
                    ]),
                    { empty: "No devices signed in." },
                ),
                { wide: true, iconName: "devices" },
            ),
            card(
                "Purchases and grants",
                h(
                    "div",
                    {},
                    table(
                        [
                            { label: "What" },
                            { label: "From" },
                            { label: "Qty", num: true },
                            { label: "State" },
                            { label: "Since" },
                            { label: "Ends" },
                            { label: "" },
                        ],
                        d.entitlements.map((e) => [
                            PRODUCTS[e.product] ?? e.product,
                            h("span", {}, SOURCES[e.source] ?? e.source, h("div", { class: "faint" }, h("code", {}, e.ref))),
                            num(e.quantity),
                            h(
                                "span",
                                { class: `pill ${e.status === "active" ? "green" : e.status === "refunded" ? "red" : "grey"}` },
                                e.status,
                            ),
                            ago(e.created_at),
                            e.expires_at ? new Date(e.expires_at).toLocaleDateString("en-CA") : "Never",
                            e.source === "grant" && e.status === "active" && isAdmin()
                                ? h(
                                      "button",
                                      { class: "btn small danger", onclick: act({ do: "revoke", ref: e.ref }, "Taken back.") },
                                      "Take back",
                                  )
                                : "",
                        ]),
                        { empty: "Nothing bought yet." },
                    ),
                    isAdmin() && !d.plan.premium && !hasGrant
                        ? h(
                              "div",
                              { class: "row", style: "margin-top:12px" },
                              h("button", { class: "btn", onclick: act({ do: "grant" }, "Premium given.") }, "Give Premium for life"),
                          )
                        : null,
                ),
                { wide: true, iconName: "revenue" },
            ),
            card(
                "Backups",
                table(
                    [{ label: "Version", num: true }, { label: "Saved" }, { label: "From" }, { label: "Size", num: true }],
                    d.backups.map((b) => [num(b.version), when(b.saved_at), b.device_name, `${(b.size / 1024).toFixed(0)} KB`]),
                    { empty: "No backups." },
                ),
                { note: "Never the contents" },
            ),
            card(
                "Codes used",
                table(
                    [{ label: "Code" }, { label: "When" }],
                    d.redemptions.map((r) => [h("code", {}, r.code), when(r.at)]),
                    { empty: "None." },
                ),
            ),
        ),
    );
}

async function installPage(id) {
    const d = await api(`installs?id=${encodeURIComponent(id)}`);
    const i = d.install;
    return h(
        "div",
        {},
        h("button", { class: "btn small back", onclick: () => go("Installs") }, icon("back"), "All installs"),
        h(
            "div",
            { class: "tiles" },
            tile("Device", h("span", { style: "font-size:17px" }, `${i.maker ?? ""} ${i.model ?? ""}`.trim() || "Unknown"), {
                note: `${i.kind ?? "?"} · Android ${i.android ?? "?"} · ${language(i.language)}`,
            }),
            tile("Version", i.version ?? "–", { note: SOURCES[i.build] ?? i.build }),
            tile("Installed", ago(i.first_seen), { note: `Last seen ${ago(i.last_seen)}` }),
            tile("Where from", i.utm_source ?? SOURCES[i.source] ?? i.source ?? "Unknown", {
                note: [i.utm_medium, i.utm_campaign, country(i.country)].filter(Boolean).join(" · "),
            }),
        ),
        h(
            "div",
            { class: "row", style: "margin-bottom:16px" },
            h("code", { class: "faint" }, i.id),
            isAdmin()
                ? h(
                      "label",
                      { class: "row" },
                      switchButton(i.test, "Test install", async (on, el) => {
                          if (await change("installs", { id: i.id, test: on }, on ? "Marked as a test install." : "Counted again."))
                              el.setAttribute("aria-checked", String(on));
                      }),
                      "Test install (left out of every number)",
                  )
                : i.test
                  ? h("span", { class: "pill amber" }, "Test install")
                  : null,
        ),
        h(
            "div",
            { class: "cards" },
            card(
                "What it did",
                bars(
                    d.counts.map((c) => ({ label: c.name, count: c.count })),
                    { label: (r) => r.label.replaceAll("_", " ") },
                ),
                { iconName: "log" },
            ),
            card(
                "Problem reports",
                d.reports.length
                    ? h(
                          "div",
                          { class: "list" },
                          d.reports.map((r) =>
                              h(
                                  "div",
                                  { class: "item" },
                                  h(
                                      "div",
                                      { class: "body" },
                                      h("div", {}, r.what || "Crash"),
                                      h("div", { class: "faint" }, `${ago(r.at)} · ${r.status}`),
                                  ),
                                  h("button", { class: "btn small", onclick: () => go("Reports") }, "Open"),
                              ),
                          ),
                      )
                    : h("div", { class: "empty" }, "None."),
            ),
            card(
                "Latest 50 events",
                table(
                    [{ label: "When" }, { label: "Event" }, { label: "Details" }],
                    d.latest.map((e) => [
                        when(e.at),
                        e.name.replaceAll("_", " "),
                        h(
                            "span",
                            { class: "faint" },
                            Object.entries(e.props ?? {})
                                .map(([k, v]) => `${k}: ${v}`)
                                .join(" · "),
                        ),
                    ]),
                    { empty: "No events." },
                ),
                { wide: true },
            ),
        ),
    );
}

// --- Frame ---

const root = document.getElementById("app");
let frame = null;

function drawCounts() {
    for (const el of document.querySelectorAll("[data-count]")) {
        const n = state.counts[el.dataset.count];
        el.textContent = n ? String(n) : "";
        el.hidden = !n;
    }
}

function navButton(t) {
    return h(
        "button",
        {
            "data-tab": t.name,
            title: t.name,
            "aria-label": t.name,
            "aria-current": state.tab === t.name ? "page" : null,
            onclick: () => go(t.name),
        },
        icon(t.icon),
        h("span", { class: "nav-label" }, t.name),
        h("span", { class: "count", "data-count": t.name, hidden: true }),
    );
}

function search() {
    const input = h("input", { type: "search", placeholder: "Email, account or install id", "aria-label": "Search" });
    return h(
        "form",
        {
            class: "search",
            role: "search",
            onsubmit: (e) => {
                e.preventDefault();
                const v = input.value.trim();
                if (!v) return;
                if (/^[0-9a-f]{32}$/.test(v)) go("Installs", { open: v });
                else if (/^[0-9a-f-]{36}$/.test(v)) go("Users", { open: v });
                else go("Users", { q: v });
                input.value = "";
            },
        },
        icon("search"),
        input,
    );
}

function buildFrame() {
    const groups = [...new Set(TABS.map((t) => t.group))];
    const brand = () =>
        h(
            "div",
            { class: "brand" },
            h("img", { src: "/admin/icon.svg", alt: "" }),
            h("span", { class: "brand-text" }, "Tuned"),
            h("span", { class: "pill" }, "Admin"),
        );
    const signOut = h(
        "button",
        {
            class: "btn small",
            onclick: async () => {
                await fetch("/api/admin/signout", { method: "POST" }).catch(() => {});
                location.href = "/admin";
            },
        },
        icon("out"),
        "Sign out",
    );
    const content = h("div", { id: "content" });
    const top = h("header", { class: "top" });
    const main = h(
        "main",
        { class: "main" },
        h("nav", { class: "tabs-row", "aria-label": "Sections" }, brand(), TABS.map(navButton)),
        top,
        content,
    );
    const rail = h(
        "aside",
        { class: "rail" },
        brand(),
        h(
            "nav",
            { class: "nav", "aria-label": "Sections" },
            groups.map((g) => [
                h("div", { class: "nav-group", "data-group": g, title: g }, g),
                TABS.filter((t) => t.group === g).map(navButton),
            ]),
        ),
    );
    frame = { el: h("div", { class: "frame" }, rail, main), content, top, signOut };
    put(root, frame.el);
}

let renderToken = 0;

async function render() {
    if (!state.me) return gate();
    if (!frame) buildFrame();
    // Focus Overview: icon rail on Overview; full labels on every other tab.
    frame.el.classList.toggle("focus-overview", state.tab === "Overview" && !state.open);
    for (const b of frame.el.querySelectorAll("nav button[data-tab]")) {
        if (b.dataset.tab === state.tab) b.setAttribute("aria-current", "page");
        else b.removeAttribute("aria-current");
    }
    const tab = TABS.find((t) => t.name === state.tab);
    put(
        frame.top,
        h("h1", {}, icon(tab.icon), state.tab),
        h("div", { class: "spacer" }),
        tab.period && !state.open
            ? h(
                  "div",
                  { class: "periods", role: "group", "aria-label": "Period" },
                  PERIODS.map(([d, l]) =>
                      h(
                          "button",
                          {
                              "aria-pressed": String(state.days === d),
                              "aria-label": `Last ${l}`,
                              onclick: () => {
                                  state.days = d;
                                  go(state.tab, { replace: true });
                              },
                          },
                          l,
                      ),
                  ),
              )
            : null,
        search(),
        h(
            "div",
            { class: "who" },
            state.me.role === "support" ? h("span", { class: "pill grey" }, "Read only") : null,
            h("span", {}, state.me.email),
            frame.signOut,
        ),
    );
    drawCounts();
    const token = ++renderToken;
    put(
        frame.content,
        bones(
            ["Reports", "To do", "Users", "Installs", "Log", "Codes", "Flags", "Automations"].includes(state.tab) && !state.open
                ? "list"
                : "tab",
        ),
    );
    try {
        const view = await SECTIONS[state.tab]();
        if (token === renderToken) put(frame.content, view);
    } catch (error) {
        if (error instanceof SignedOut) return gate();
        console.error(error);
        if (token === renderToken)
            put(
                frame.content,
                h(
                    "div",
                    { class: "card" },
                    h(
                        "div",
                        { class: "empty" },
                        error.status === 404 ? "Not found." : "This didn't load. The database may not be set up yet: see System.",
                    ),
                    h(
                        "div",
                        { class: "row", style: "justify-content:center" },
                        h("button", { class: "btn", onclick: render }, "Try again"),
                    ),
                ),
            );
    }
}

// --- Sign-in and the second step ---

function gateCard(...children) {
    frame = null;
    put(
        root,
        h(
            "div",
            { class: "gate" },
            h(
                "div",
                { class: "card" },
                h("img", { src: "/admin/icon.svg", alt: "", width: 44, height: 44, style: "border-radius:11px" }),
                ...children,
            ),
        ),
    );
}

async function gate() {
    state.me = null;
    let s;
    try {
        s = await api("session");
    } catch (error) {
        s = error.data ?? { signedIn: false, ready: false };
    }
    if (!s.signedIn) {
        if (s.refused)
            return gateCard(
                h("h1", {}, "Not on the list"),
                h(
                    "p",
                    {},
                    `${s.refused} isn't an admin address. Add it to ADMIN_EMAILS or SUPPORT_EMAILS on Vercel, or sign in with another Google account.`,
                ),
                h("a", { class: "btn primary", href: "/api/admin/signin" }, "Use another account"),
            );
        return gateCard(
            h("h1", {}, "Tuned admin"),
            h(
                "p",
                {},
                s.ready
                    ? "Sign in with the Google account on the admin list."
                    : "Sign-in isn't set up yet: it needs Neon, Google's web client and ADMIN_EMAILS on Vercel.",
            ),
            h(
                "a",
                {
                    class: "gsi",
                    href: "/api/admin/signin",
                    "aria-disabled": s.ready ? null : "true",
                    style: s.ready ? null : "pointer-events:none;opacity:.5",
                },
                h("img", { src: "/google.svg", alt: "", width: 20, height: 20 }),
                "Sign in with Google",
            ),
        );
    }
    if (s.twoFactor === "setup") return setupTwoFactor(s);
    if (s.twoFactor === "code") return askCode(s);
    state.me = s;
    render();
    // The counts beside Reports and To do, read once at sign-in and again by those tabs and Overview.
    api("todo")
        .then((d) => {
            state.counts["To do"] = d.items.length;
            state.counts.Reports = d.items.find((i) => i.key.startsWith("reports:"))
                ? Number(d.items.find((i) => i.key.startsWith("reports:")).key.split(":")[1])
                : 0;
            drawCounts();
        })
        .catch(() => {});
}

function codeForm(onCode, label = "6-digit code") {
    const input = h("input", {
        class: "plain",
        inputmode: "numeric",
        autocomplete: "one-time-code",
        maxlength: 9,
        "aria-label": label,
        placeholder: "000000",
    });
    const error = h("p", { class: "faint", role: "alert" });
    const form = h(
        "form",
        {
            onsubmit: async (e) => {
                e.preventDefault();
                error.textContent = "";
                try {
                    await onCode(input.value.trim());
                } catch (err) {
                    error.textContent = err.status === 429 ? "Too many tries. Wait 15 minutes." : "That code didn't work.";
                    input.select();
                }
            },
        },
        input,
        error,
        h("button", { class: "btn primary", type: "submit", style: "width:100%" }, "Continue"),
    );
    setTimeout(() => input.focus(), 0);
    return form;
}

async function setupTwoFactor(s) {
    const setup = await api("two-factor", { method: "POST", body: { action: "setup" } });
    gateCard(
        h("h1", {}, "Set up two-step sign-in"),
        h(
            "p",
            {},
            `Signed in as ${s.email}. Add this key to an authenticator app (Google Authenticator, 1Password, Authy), then type the code it shows.`,
        ),
        h("code", { class: "secret" }, setup.secret.match(/.{1,4}/g).join(" ")),
        h("p", { class: "faint", style: "margin-top:8px" }, h("a", { href: setup.link }, "Open in an authenticator app on this device")),
        codeForm(async (code) => {
            const done = await api("two-factor", { method: "POST", body: { action: "enable", code } });
            gateCard(
                h("h1", {}, "Keep these recovery codes"),
                h("p", {}, "Each one works once, if you lose your authenticator. They won't be shown again."),
                h(
                    "div",
                    { class: "codes" },
                    done.recovery.map((c) => h("code", {}, c)),
                ),
                h(
                    "button",
                    {
                        class: "btn",
                        style: "width:100%;margin-bottom:8px",
                        onclick: () => navigator.clipboard?.writeText(done.recovery.join("\n")).then(() => toast("Copied.")),
                    },
                    "Copy them",
                ),
                h("button", { class: "btn primary", style: "width:100%", onclick: gate }, "I've saved them"),
            );
        }),
    );
}

function askCode(s) {
    let recovery = false;
    const draw = () =>
        gateCard(
            h("h1", {}, recovery ? "Use a recovery code" : "Two-step sign-in"),
            h(
                "p",
                {},
                recovery
                    ? "Type one of the codes you saved when you set this up."
                    : `Signed in as ${s.email}. Type the code from your authenticator app.`,
            ),
            codeForm(
                async (code) => {
                    await api("two-factor", {
                        method: "POST",
                        body: recovery ? { action: "verify", recovery: code } : { action: "verify", code },
                    });
                    gate();
                },
                recovery ? "Recovery code" : "6-digit code",
            ),
            h(
                "p",
                { style: "margin:12px 0 0;text-align:center" },
                h(
                    "button",
                    { class: "btn small", onclick: () => ((recovery = !recovery), draw()) },
                    recovery ? "Use the authenticator instead" : "Use a recovery code",
                ),
            ),
        );
    draw();
}

readUrl();
watchSelects();
gate();
