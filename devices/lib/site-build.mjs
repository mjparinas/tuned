// Builds the public website from devices/site/ (docs/decisions/website.md): the landing page, the blog (one page per
// Markdown post, an index per folder), sitemap.xml and features.json. Plain functions with no file access, so the test
// can check them; devices/build-site.mjs reads the sources and writes devices/public/. No dependencies and no build step
// on Vercel: the output is committed, and a test fails when it is out of date.

/** Escapes text for HTML. */
export const esc = (s) =>
    String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

/** Splits `---` frontmatter (simple `key: value` lines) from the body. */
export function parseFrontmatter(text) {
    const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text);
    if (!m) return { data: {}, body: text };
    const data = {};
    for (const line of m[1].split(/\r?\n/)) {
        const kv = /^([A-Za-z][\w-]*):\s*(.*)$/.exec(line);
        if (!kv) continue;
        let v = kv[2].trim();
        if (/^(["']).*\1$/.test(v)) v = v.slice(1, -1);
        data[kv[1]] = v;
    }
    return { data, body: m[2] };
}

/** Inline Markdown: code, images, links, bold, italic. Text is escaped first. */
export function inline(text) {
    const codes = [];
    let s = esc(text).replace(/`([^`]+)`/g, (_, c) => {
        codes.push(c);
        return `\u0000${codes.length - 1}\u0000`;
    });
    s = s
        .replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, '<img src="$2" alt="$1" loading="lazy" />')
        .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label, href) => {
            const external = /^https?:\/\//.test(href) && !href.startsWith("https://www.tunedplayer.com");
            return `<a href="${href}"${external ? ' rel="noopener"' : ""}>${label}</a>`;
        })
        .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
        .replace(/(^|[^*\w])\*([^*\s][^*]*)\*/g, "$1<em>$2</em>")
        .replace(/(^|[^_\w])_([^_\s][^_]*)_(?!\w)/g, "$1<em>$2</em>");
    return s.replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${codes[Number(i)]}</code>`);
}

const tableRow = (line) =>
    line
        .trim()
        .replace(/^\|/, "")
        .replace(/\|$/, "")
        .split("|")
        .map((c) => c.trim());

/** Block Markdown: headings, paragraphs, lists, tables, quotes, rules; lines starting with < pass through as HTML. */
export function markdown(md) {
    const lines = md.replace(/\r\n/g, "\n").split("\n");
    const out = [];
    let i = 0;
    const isBlank = (l) => l === undefined || l.trim() === "";
    while (i < lines.length) {
        const line = lines[i];
        if (isBlank(line)) {
            i++;
            continue;
        }
        const h = /^(#{1,4})\s+(.*)$/.exec(line);
        if (h) {
            const level = Math.max(2, h[1].length); // the page's title is the only h1
            const id = slug(h[2]);
            out.push(`<h${level} id="${id}">${inline(h[2])}</h${level}>`);
            i++;
            continue;
        }
        if (/^\s*</.test(line)) {
            while (i < lines.length && !isBlank(lines[i])) out.push(lines[i++]);
            continue;
        }
        if (/^(-{3,}|\*{3,})\s*$/.test(line)) {
            out.push("<hr />");
            i++;
            continue;
        }
        if (/^\s*\|/.test(line) && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) {
            const head = tableRow(line);
            const aligns = tableRow(lines[i + 1]).map((c) => (c.endsWith(":") ? (c.startsWith(":") ? "center" : "right") : ""));
            i += 2;
            const rows = [];
            while (i < lines.length && /^\s*\|/.test(lines[i])) rows.push(tableRow(lines[i++]));
            // A table whose top-left cell is empty has row headings in its first column (the comparisons' "Platforms",
            // "Price" and so on), so those are <th scope="row"> and the empty corner is a plain cell: a heading with no
            // text reads as nothing to a screen reader (WCAG 1.3.1, 2026-11-20 audit). The box scrolls sideways on a
            // phone, so it can take focus and has a name, letting a keyboard scroll it too (2.1.1).
            const rowHeads = head[0] === "";
            const cell = (tag, c, j, scope) =>
                `<${tag}${scope ? ` scope="${scope}"` : ""}${aligns[j] ? ` style="text-align:${aligns[j]}"` : ""}>${inline(c)}</${tag}>`;
            const headCell = (c, j) => (c === "" ? cell("td", c, j) : cell("th", c, j, "col"));
            const bodyCell = (c, j) => (rowHeads && j === 0 ? cell("th", c, j, "row") : cell("td", c, j));
            out.push(
                `<div class="table" tabindex="0" role="region" aria-label="Table"><table><thead><tr>${head.map(headCell).join("")}</tr></thead><tbody>` +
                    rows.map((r) => `<tr>${r.map(bodyCell).join("")}</tr>`).join("") +
                    "</tbody></table></div>",
            );
            continue;
        }
        const list = /^\s*([-*]|\d+\.)\s+/.exec(line);
        if (list) {
            const ordered = /\d/.test(list[1]);
            const items = [];
            while (i < lines.length && /^\s*([-*]|\d+\.)\s+/.test(lines[i])) {
                let item = lines[i++].replace(/^\s*([-*]|\d+\.)\s+/, "");
                while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*([-*]|\d+\.)\s+/.test(lines[i]))
                    item += ` ${lines[i++].trim()}`;
                items.push(`<li>${inline(item)}</li>`);
            }
            out.push(`<${ordered ? "ol" : "ul"}>${items.join("")}</${ordered ? "ol" : "ul"}>`);
            continue;
        }
        if (/^>\s?/.test(line)) {
            const quote = [];
            while (i < lines.length && /^>\s?/.test(lines[i])) quote.push(lines[i++].replace(/^>\s?/, ""));
            out.push(`<blockquote>${markdown(quote.join("\n"))}</blockquote>`);
            continue;
        }
        const para = [];
        while (i < lines.length && !isBlank(lines[i]) && !/^(#{1,4}\s|\s*<|\s*\||\s*([-*]|\d+\.)\s+|>)/.test(lines[i]))
            para.push(lines[i++].trim());
        if (para.length === 0) para.push(lines[i++].trim());
        out.push(`<p>${inline(para.join(" "))}</p>`);
    }
    return out.join("\n");
}

/** A URL-safe slug. */
export const slug = (s) =>
    String(s)
        .toLowerCase()
        .replace(/<[^>]+>/g, "")
        .replace(/&[a-z]+;/g, "")
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-|-$/g, "");

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

/** "2026-11-19" -> "November 19, 2026". */
export function longDate(iso) {
    const [y, m, d] = iso.split("-").map(Number);
    return `${MONTHS[m - 1]} ${d}, ${y}`;
}

/** What each kind of post is called in the blog's sections, in the order they appear. */
export const KINDS = [
    { kind: "release", title: "What's new", blurb: "What changed in each release of Tuned, and when." },
    { kind: "guide", title: "Guides", blurb: "Getting set up, and getting the most out of Tuned." },
    { kind: "compare", title: "Comparisons", blurb: "How Tuned compares with other IPTV players." },
    { kind: "roundup", title: "Roundups", blurb: "Lists of apps and devices worth knowing." },
];
const kindTitle = (kind) => KINDS.find((k) => k.kind === kind)?.title ?? kind.charAt(0).toUpperCase() + kind.slice(1);

/** A post from its source path (relative to site/posts, like "whats-new/tuned-1-13.md") and text. */
export function readPost(path, text) {
    const { data, body } = parseFrontmatter(text);
    const parts = path.replace(/\.md$/, "").split("/");
    const url = `/blog/${parts.join("/")}/`;
    for (const key of ["title", "description", "date"]) if (!data[key]) throw new Error(`${path}: frontmatter needs ${key}`);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(data.date)) throw new Error(`${path}: date must be YYYY-MM-DD`);
    return { ...data, kind: data.kind || "post", path, url, folder: parts.slice(0, -1).join("/"), html: markdown(body) };
}

/** Newest first, then by title. */
export const byDate = (a, b) =>
    b.date.localeCompare(a.date) ||
    (b.version ?? "").localeCompare(a.version ?? "", undefined, { numeric: true }) ||
    a.title.localeCompare(b.title);

// ---------------------------------------------------------------- Release posts

const DROP_TYPES = new Set(["ci", "test", "docs", "chore", "build", "refactor", "style", "revert"]);
// Work that only the developer or the server's admin sees.
const BEHIND_THE_SCENES =
    /\b(admin|vercel|github|ci|decision record|smoke test|lint|baseline profile|commit hash)\b|^promote |^bring main|^bump |first contribution/i;

/** One line of GitHub's generated notes ("* feat: x by @y in url") as { type, text }, or null. */
export function noteLine(line) {
    const m = /^\s*\*\s+(.*?)(?:\s+by\s+@\S+\s+in\s+\S+)?\s*$/.exec(line);
    if (!m || /made their first contribution/.test(m[1])) return null;
    const typed = /^(\w+)(?:\([^)]*\))?!?:\s*(.*)$/.exec(m[1]);
    const type = typed ? typed[1].toLowerCase() : "feat";
    const text = (typed ? typed[2] : m[1]).trim();
    if (DROP_TYPES.has(type) || BEHIND_THE_SCENES.test(text) || !text) return null;
    return { type, text: text.charAt(0).toUpperCase() + text.slice(1) };
}

/** "v1.13.0" -> "1.13.0"; "v1.1.0-build.4" -> "1.1.0". */
export const versionOf = (tag) => tag.replace(/^v/, "").split("-")[0];

/** The post's file path, under site/posts, for a release tag: whats-new/tuned-1-13-0.md. */
export const releasePostPath = (tag) => `whats-new/tuned-${versionOf(tag).replace(/\./g, "-")}.md`;

/** The Markdown post for a release, from GitHub's generated notes. `intro` is an optional hand-written opening. */
export function releasePost({ tag, date, notes, intro }) {
    const version = versionOf(tag);
    const lines = notes.split("\n").map(noteLine).filter(Boolean);
    const groups = [
        ["New", lines.filter((l) => l.type === "feat")],
        ["Faster", lines.filter((l) => l.type === "perf")],
        ["Fixed", lines.filter((l) => !["feat", "perf"].includes(l.type))],
    ].filter(([, items]) => items.length);
    const counts = [
        groups.find(([g]) => g === "New") && `${groups.find(([g]) => g === "New")[1].length} new`,
        groups.find(([g]) => g === "Faster") && "faster",
        groups.find(([g]) => g === "Fixed") && `${groups.find(([g]) => g === "Fixed")[1].length} fixed`,
    ].filter(Boolean);
    const lead = intro?.trim() || `Tuned ${version} is out${counts.length ? `: ${counts.join(", ")}` : ""}.`;
    const description = lead.split(/(?<=\.)\s/)[0].replace(/"/g, "'");
    const body = groups.map(([g, items]) => `## ${g}\n\n${items.map((l) => `- ${l.text}`).join("\n")}`).join("\n\n");
    return `---\ntitle: What's new in Tuned ${version}\ndescription: "${description}"\ndate: ${date}\nkind: release\nversion: ${version}\n---\n\n${lead}\n\n${body}${body ? "\n" : ""}`;
}

// ---------------------------------------------------------------- Pages

const ICONS = {
    playlist: '<path d="M4 6h12M4 11h12M4 16h7"/><path d="M17 14v6l4-3z" fill="currentColor" stroke="none"/>',
    live: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="m8 2 4 4 4-4"/>',
    film: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4"/>',
    play: '<circle cx="12" cy="12" r="9"/><path d="M10 8.5v7l6-3.5z" fill="currentColor" stroke="none"/>',
    tv: '<rect x="2" y="5" width="20" height="13" rx="2"/><path d="M8 21h8"/>',
    people: '<circle cx="9" cy="8" r="3.2"/><path d="M3 20c.6-3.5 3-5.5 6-5.5s5.4 2 6 5.5"/><circle cx="17" cy="9" r="2.5"/><path d="M16.5 14.6c2.3.3 4 2 4.5 4.9"/>',
    lock: '<rect x="4" y="10.5" width="16" height="10" rx="2"/><path d="M8 10.5V7a4 4 0 0 1 8 0v3.5"/>',
    check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
};
export const icon = (name, size = 22) =>
    `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name]}</svg>`;

const PLAY_LOGO =
    '<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path fill="#4285F4" d="M3.6 1.8 13.5 12l-9.9 10.2c-.4-.2-.6-.6-.6-1.1V2.9c0-.5.2-.9.6-1.1z"/><path fill="#34A853" d="M3.6 1.8c.3-.2.8-.2 1.2.1l11.9 6.8-3.2 3.3z"/><path fill="#EA4335" d="m13.5 12 3.2 3.3-11.9 6.8c-.4.3-.9.3-1.2.1z"/><path fill="#FBBC04" d="m16.7 8.7 3.7 2.1c.9.5.9 1.9 0 2.4l-3.7 2.1-3.2-3.3z"/></svg>';
const APPLE_LOGO =
    '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor" aria-hidden="true"><path d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11z"/></svg>';
const TV_LOGO =
    '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></svg>';
const ANDROID_LOGO =
    '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true"><path d="M17.6 9.48 19.44 6.3a.38.38 0 0 0-.66-.38l-1.86 3.22a11.5 11.5 0 0 0-9.84 0L5.22 5.92a.38.38 0 0 0-.66.38L6.4 9.48A10.8 10.8 0 0 0 1 18h22a10.8 10.8 0 0 0-5.4-8.52M7 15.25a1.25 1.25 0 1 1 0-2.5 1.25 1.25 0 0 1 0 2.5m10 0a1.25 1.25 0 1 1 0-2.5 1.25 1.25 0 0 1 0 2.5"/></svg>';

/** The store badges, in the stores' standard black style; a store without a listing yet shows "Coming soon". */
export function storeBadges(site, place) {
    const badge = (href, store, small, big, logo, cta) => {
        const body = `${logo}<span class="badge-text"><small>${small}</small><b>${big}</b></span>`;
        if (href) return `<a class="store-badge" href="${href}" rel="noopener" data-cta="${cta}-${place}">${body}</a>`;
        return `<span class="store-badge soon" role="img" aria-label="${esc(store)}: coming soon"><span aria-hidden="true" class="badge-inner">${body}</span><span class="soon-pill" aria-hidden="true">Coming soon</span></span>`;
    };
    return `<div class="badges">
${badge(site.stores.googlePlay, "Google Play", "GET IT ON", "Google Play", PLAY_LOGO, "google-play")}
${badge(site.stores.appStore, "App Store for iPhone and iPad", "Download on the", "App Store", APPLE_LOGO, "app-store")}
${badge(site.stores.macAppStore, "Mac App Store", "Download on the", "Mac App Store", APPLE_LOGO, "mac-app-store")}
${badge(site.stores.samsungTv, "Samsung TV", "COMING TO", "Samsung TV", TV_LOGO, "samsung-tv")}
</div>`;
}

/** The main download button: /download, the newest Android release. */
export const downloadButton = (site, place, label = "Download for Android") =>
    `<a class="button big" href="${site.apk}" data-cta="apk-${place}">${ANDROID_LOGO}<span>${label}</span></a>`;

/** The call to action under every post and at the foot of the landing page. */
export function ctaCard(site, place, version) {
    return `<section class="cta-card" aria-labelledby="cta-${place}">
<img src="/icon.svg" alt="" width="64" height="64" />
<div>
<h2 id="cta-${place}">Get Tuned for Android</h2>
<p>A fast, quiet IPTV player for phones, tablets and Android TV. Free, with no ads.${version ? ` The latest version is ${esc(version)}.` : ""}</p>
<div class="actions">${downloadButton(site, place)}<a class="button ghost" href="/#features" data-cta="features-${place}">See every feature</a></div>
<p class="fine">For Android phones, tablets and TVs. Google Play, iPhone, iPad, Mac and Samsung TV are coming soon.</p>
</div>
</section>`;
}

/** The header every page of the website shares: the landing page, the blog, and the account and sign-in pages. */
export const siteHeader = (site) => `<header class="top">
<nav class="wrap nav" aria-label="Main">
<a class="logo" href="/"><img src="/icon.svg" alt="" width="36" height="36" /><span>${site.name}</span></a>
<div class="links">
<a href="/#features">Features</a>
<a href="/blog/">Blog</a>
<a href="/account">Account</a>
</div>
<a class="button small" href="${site.apk}" data-cta="apk-header">Download</a>
</nav>
</header>`;

/** The footer every page shares. */
export const siteFooter = (site) => `<footer class="foot">
<div class="wrap foot-grid">
<div>
<a class="logo" href="/"><img src="/icon.svg" alt="" width="32" height="32" /><span>${site.name}</span></a>
<p>A fast, quiet IPTV player for Android. Tuned doesn't sell or include any channels: bring the playlist from your own TV provider.</p>
</div>
<nav aria-label="Tuned"><h2>Tuned</h2><a href="/#features">Features</a><a href="/blog/whats-new/">What's new</a><a href="${site.apk}">Download for Android</a></nav>
<nav aria-label="Help"><h2>Help</h2><a href="/blog/">Blog</a><a href="/account">Your Tuned account</a><a href="/devices">Lost your devices?</a><a href="${site.privacy}" rel="noopener">Privacy</a></nav>
</div>
<p class="wrap copy">© 2026 ${site.name}. Android, Google Play and the Google Play logo are trademarks of Google LLC. Apple, the Apple logo, iPhone, iPad and Mac are trademarks of Apple Inc. Samsung is a trademark of Samsung Electronics.</p>
</footer>`;

/**
 * /chrome.js, for the hand-written pages (account, TV sign-in, devices, provider check, signed in): each includes it
 * twice, `<script src="/chrome.js" data-part="header"></script>` first thing in <body> and `data-part="footer"` last,
 * and it writes the same header and footer as the generated pages in place, before the page draws.
 */
export function chromeScript(site) {
    const parts = { header: siteHeader(site), footer: siteFooter(site) };
    return `// Generated by devices/build-site.mjs (docs/decisions/website.md): the site's shared header and footer.
(() => {
    const parts = ${JSON.stringify(parts)};
    const me = document.currentScript;
    const html = me && parts[me.dataset.part];
    if (html) me.insertAdjacentHTML("afterend", html);
})();
`;
}

/** The whole page around a body. */
export function layout(site, { title, description, path, body, type = "website", jsonLd = [], fullTitle = false, image = "/img/og.png" }) {
    const url = `${site.url}${path}`;
    const pageTitle = fullTitle ? title : `${title} · ${site.name}`;
    const ld = jsonLd.map((j) => `<script type="application/ld+json">${JSON.stringify(j).replace(/</g, "\\u003c")}</script>`).join("\n");
    return `<!doctype html>
<!-- Generated by devices/build-site.mjs from devices/site/. Edit the sources and run: node devices/build-site.mjs -->
<html lang="en-CA">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>${esc(pageTitle)}</title>
<meta name="description" content="${esc(description)}" />
<link rel="canonical" href="${url}" />
<meta property="og:type" content="${type}" />
<meta property="og:site_name" content="${site.name}" />
<meta property="og:title" content="${esc(title)}" />
<meta property="og:description" content="${esc(description)}" />
<meta property="og:url" content="${url}" />
<meta property="og:image" content="${site.url}${image}" />
<meta name="twitter:card" content="summary_large_image" />
<meta name="theme-color" content="#fcfaff" media="(prefers-color-scheme: light)" />
<meta name="theme-color" content="#121018" media="(prefers-color-scheme: dark)" />
<link rel="icon" href="/favicon.ico" sizes="48x48" />
<link rel="icon" href="/icon.svg" type="image/svg+xml" />
<link rel="apple-touch-icon" href="/apple-touch-icon.png" />
<link rel="alternate" type="application/rss+xml" title="${site.name} blog" href="/blog/feed.xml" />
<link rel="stylesheet" href="/site.css" />
<link rel="stylesheet" href="/web.css" />
${ld}
</head>
<body class="web">
<a class="skip" href="#main">Skip to the content</a>
${siteHeader(site)}
<main id="main">
${body}
</main>
${siteFooter(site)}
<script src="/beacon.js" defer></script>
</body>
</html>
`;
}

/** A card linking to a post. */
// `level` is the card's heading level: 3 under a section's h2, 2 straight under a folder page's h1, so headings never
// skip a level (WCAG 1.3.1, 2026-11-20 audit).
export const postCard = (p, level = 3) =>
    `<li class="post-card"><a href="${p.url}"><span class="kind">${esc(kindTitle(p.kind))}</span><h${level}>${esc(p.title)}</h${level}><p>${esc(p.description)}</p><time datetime="${p.date}">${longDate(p.date)}</time></a></li>`;

/** A post's own page. */
export function postPage(site, post, { latestVersion, related = [] }) {
    const section = KINDS.find((k) => k.kind === post.kind);
    const crumbs = [`<a href="/blog/">Blog</a>`];
    if (post.folder) crumbs.push(`<a href="/blog/${post.folder}/">${esc(section?.title ?? post.folder)}</a>`);
    const body = `<article class="wrap post">
<nav class="crumbs" aria-label="Breadcrumb">${crumbs.join(' <span aria-hidden="true">›</span> ')}</nav>
<h1>${esc(post.title)}</h1>
<p class="meta"><time datetime="${post.date}">${longDate(post.date)}</time></p>
<div class="prose">
${post.html}
</div>
${ctaCard(site, "post", latestVersion)}
${related.length ? `<section aria-labelledby="more"><h2 id="more" class="section-title">More from the blog</h2><ul class="post-grid">${related.map((p) => postCard(p)).join("\n")}</ul></section>` : ""}
</article>`;
    return layout(site, {
        title: post.title,
        description: post.description,
        path: post.url,
        type: "article",
        body,
        jsonLd: [
            {
                "@context": "https://schema.org",
                "@type": "BlogPosting",
                headline: post.title,
                description: post.description,
                datePublished: post.date,
                url: `${site.url}${post.url}`,
                author: { "@type": "Organization", name: site.name, url: site.url },
                publisher: {
                    "@type": "Organization",
                    name: site.name,
                    logo: { "@type": "ImageObject", url: `${site.url}/apple-touch-icon.png` },
                },
            },
        ],
    });
}

/** The blog's front page (every post, by section) or one folder's page. */
export function indexPage(site, { posts, folder, latestVersion }) {
    const sorted = [...posts].sort(byDate);
    let title, description, sections;
    if (folder) {
        const kind = sorted[0]?.kind;
        const meta = KINDS.find((k) => k.kind === kind);
        title = meta?.title ?? folder;
        description = meta?.blurb ?? `${title} from the ${site.name} blog.`;
        sections = `<ul class="post-grid">${sorted.map((p) => postCard(p, 2)).join("\n")}</ul>`;
    } else {
        title = "Blog";
        description = `News, guides and what's new in each release of ${site.name}, the IPTV player for Android.`;
        const kinds = [...KINDS.map((k) => k.kind), ...new Set(sorted.map((p) => p.kind))].filter((k, i, a) => a.indexOf(k) === i);
        sections = kinds
            .map((kind) => {
                const list = sorted.filter((p) => p.kind === kind);
                if (!list.length) return "";
                const meta = KINDS.find((k) => k.kind === kind);
                const folderOf = list[0].folder;
                const all =
                    folderOf && list.every((p) => p.folder === folderOf)
                        ? `<a class="more" href="/blog/${folderOf}/">All ${esc(kindTitle(kind).toLowerCase())} ›</a>`
                        : "";
                return `<section aria-labelledby="k-${kind}"><div class="section-head"><h2 id="k-${kind}" class="section-title">${esc(kindTitle(kind))}</h2>${all}</div>${meta ? `<p class="lede">${esc(meta.blurb)}</p>` : ""}<ul class="post-grid">${list
                    .slice(0, 6)
                    .map((p) => postCard(p))
                    .join("\n")}</ul></section>`;
            })
            .join("\n");
    }
    const path = folder ? `/blog/${folder}/` : "/blog/";
    const crumbs = folder ? `<nav class="crumbs" aria-label="Breadcrumb"><a href="/blog/">Blog</a></nav>` : "";
    const body = `<div class="wrap blog">
${crumbs}
<h1>${esc(title)}</h1>
<p class="lede">${esc(description)}</p>
${sections}
${ctaCard(site, "blog", latestVersion)}
</div>`;
    return layout(site, { title: folder ? `${title} · Blog` : title, description, path, body });
}

/** The landing page: the template with the features, the badges and the newest posts filled in. */
export function homePage(site, { template, groups, posts, latestVersion }) {
    const features = groups
        .map(
            (g) => `<section class="feature-group" aria-labelledby="fg-${g.id}">
<h3 id="fg-${g.id}"><span class="fg-icon">${icon(g.icon)}</span>${esc(g.title)}</h3>
<ul>
${g.items.map((f) => `<li><span class="tick">${icon("check", 18)}</span><span><b>${esc(f.title)}</b>${f.premium ? ' <span class="pill">Premium</span>' : ""}<br /><span class="muted">${esc(f.body)}</span></span></li>`).join("\n")}
</ul>
</section>`,
        )
        .join("\n");
    const count = groups.reduce((n, g) => n + g.items.length, 0);
    const latest = homePosts(site, posts)
        .map((p) => postCard(p))
        .join("\n");
    const body = template
        .replaceAll("<!-- @features -->", features)
        .replaceAll("<!-- @feature-count -->", String(count))
        .replaceAll("<!-- @badges-soon -->", storeBadges(site, "soon"))
        .replaceAll("<!-- @download-hero -->", downloadButton(site, "hero"))
        .replaceAll("<!-- @latest-posts -->", latest)
        .replaceAll("<!-- @cta -->", ctaCard(site, "home"));
    const description =
        "Tuned is a fast, quiet IPTV player for Android phones, tablets and Android TV. Xtream Codes, M3U and MAC portals, a full TV guide, catch-up, downloads and casting. No ads.";
    return layout(site, {
        title: "Tuned: a fast IPTV player for Android and Android TV",
        fullTitle: true,
        description,
        path: "/",
        body,
        jsonLd: [
            {
                "@context": "https://schema.org",
                "@type": "SoftwareApplication",
                name: site.name,
                operatingSystem: "Android 8.0 or later",
                applicationCategory: "MultimediaApplication",
                description,
                url: site.url,
                downloadUrl: site.url + site.apk,
                softwareVersion: latestVersion,
                offers: { "@type": "Offer", price: "0", priceCurrency: "USD" },
            },
            {
                "@context": "https://schema.org",
                "@type": "Organization",
                name: site.name,
                url: site.url,
                logo: `${site.url}/apple-touch-icon.png`,
            },
            { "@context": "https://schema.org", "@type": "WebSite", name: site.name, url: site.url },
            ...faqJsonLd(template),
        ],
    });
}

/**
 * The three posts in "From the blog" on the landing page: the ones named in site.homePosts, for someone new to Tuned,
 * then the newest posts that aren't release notes to fill any gap (Melvin, 2026-11-20).
 */
export function homePosts(site, posts) {
    const picked = (site.homePosts ?? []).map((path) => posts.find((p) => p.path === path)).filter(Boolean);
    const rest = [...posts].sort(byDate).filter((p) => p.kind !== "release" && !picked.includes(p));
    return [...picked, ...rest].slice(0, 3);
}

/** FAQPage structured data from the landing page's questions (each <details> with a <summary>), so search can show them. */
export function faqJsonLd(template) {
    const text = (html) =>
        html
            .replace(/<[^>]+>/g, "")
            .replace(/\s+/g, " ")
            .trim();
    const questions = [...template.matchAll(/<details>\s*<summary>([\s\S]*?)<\/summary>([\s\S]*?)<\/details>/g)].map(([, q, a]) => ({
        "@type": "Question",
        name: text(q),
        acceptedAnswer: { "@type": "Answer", text: text(a) },
    }));
    return questions.length ? [{ "@context": "https://schema.org", "@type": "FAQPage", mainEntity: questions }] : [];
}

/** sitemap.xml: the landing page, the blog's indexes and every post. */
/**
 * /privacy/, from docs/privacy.md (the one privacy policy, also published with docs/): its "# " title becomes the page's
 * heading and its "Last updated" line the date under it (Melvin, 2026-11-20: "Fix the privacy page").
 */
export function privacyPage(site, text) {
    const lines = text.replace(/\r\n/g, "\n").split("\n");
    const title = (lines.find((l) => l.startsWith("# ")) ?? "# Privacy policy").slice(2).trim();
    const updated = lines
        .find((l) => /^Last updated:/i.test(l))
        ?.replace(/^Last updated:\s*/i, "")
        .trim();
    const rest = lines.filter((l) => !l.startsWith("# ") && !/^Last updated:/i.test(l)).join("\n");
    const body = `<article class="wrap post">
<h1>${esc(title)}</h1>
${updated ? `<p class="meta">Last updated ${esc(updated)}</p>` : ""}
<div class="prose">
${markdown(rest)}
</div>
</article>`;
    return layout(site, {
        title,
        description:
            "What Tuned stores, what it sends and to whom: your logins stay encrypted on your device, and nothing is sold or used for ads.",
        path: "/privacy/",
        body,
    });
}

export function sitemap(site, { posts, folders }) {
    const newest = [...posts].sort(byDate)[0]?.date;
    const entry = (path, lastmod, priority) =>
        `  <url><loc>${site.url}${path}</loc>${lastmod ? `<lastmod>${lastmod}</lastmod>` : ""}<priority>${priority}</priority></url>`;
    const lines = [entry("/", newest, "1.0"), entry("/blog/", newest, "0.8"), entry("/privacy/", undefined, "0.3")];
    for (const f of folders) lines.push(entry(`/blog/${f}/`, [...posts].filter((p) => p.folder === f).sort(byDate)[0]?.date, "0.6"));
    for (const p of [...posts].sort(byDate)) lines.push(entry(p.url, p.date, p.kind === "release" ? "0.5" : "0.7"));
    return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${lines.join("\n")}\n</urlset>\n`;
}

/** An RSS feed of the newest 30 posts. */
export function feed(site, posts) {
    const items = [...posts]
        .sort(byDate)
        .slice(0, 30)
        .map(
            (p) =>
                `<item><title>${esc(p.title)}</title><link>${site.url}${p.url}</link><guid>${site.url}${p.url}</guid><pubDate>${new Date(`${p.date}T12:00:00Z`).toUTCString()}</pubDate><description>${esc(p.description)}</description></item>`,
        )
        .join("\n");
    return `<?xml version="1.0" encoding="UTF-8"?>\n<rss version="2.0"><channel><title>${site.name} blog</title><link>${site.url}/blog/</link><description>News, guides and what's new in ${site.name}.</description><language>en-ca</language>\n${items}\n</channel></rss>\n`;
}

/**
 * Everything the site is built from, as { path under public/: contents }. `posts` is [{ path, text }] from site/posts,
 * `template` is site/home.html.
 */
export function buildSite(site, { template, groups, posts: sources, privacy }) {
    const posts = sources.map(({ path, text }) => readPost(path, text));
    const seen = new Set();
    for (const p of posts) {
        if (seen.has(p.url)) throw new Error(`Two posts at ${p.url}`);
        seen.add(p.url);
    }
    const latestVersion = posts
        .filter((p) => p.kind === "release" && p.version)
        .map((p) => p.version)
        .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))[0];
    const folders = [...new Set(posts.map((p) => p.folder).filter(Boolean))].sort();
    const files = {
        "index.html": homePage(site, { template, groups, posts, latestVersion }),
        ...(privacy ? { "privacy/index.html": privacyPage(site, privacy) } : {}),
        "blog/index.html": indexPage(site, { posts, latestVersion }),
        "blog/feed.xml": feed(site, posts),
        "sitemap.xml": sitemap(site, { posts, folders }),
        "features.json": `${JSON.stringify({ groups }, null, 2)}\n`,
        "chrome.js": chromeScript(site),
    };
    for (const f of folders)
        files[`blog/${f}/index.html`] = indexPage(site, { posts: posts.filter((p) => p.folder === f), folder: f, latestVersion });
    for (const p of posts) {
        const related = posts
            .filter((o) => o !== p && o.kind === p.kind)
            .sort(byDate)
            .slice(0, 3);
        files[`${p.url.slice(1)}index.html`] = postPage(site, p, { latestVersion, related });
    }
    return files;
}
