import { test } from "node:test";
import assert from "node:assert/strict";
import { check } from "../build-site.mjs";
import { SITE } from "../site/config.mjs";
import {
    buildSite,
    faqJsonLd,
    homePosts,
    inline,
    markdown,
    noteLine,
    parseFrontmatter,
    readPost,
    relatedPosts,
    releaseDescription,
    releasePost,
    releasePostPath,
    sitemap,
} from "./site-build.mjs";

test("the committed website matches its sources (run node devices/build-site.mjs)", () => {
    assert.deepEqual(check(), []);
});

test("frontmatter", () => {
    const { data, body } = parseFrontmatter('---\ntitle: "A: b"\ndate: 2026-11-19\n---\n\nHello');
    assert.equal(data.title, "A: b");
    assert.equal(data.date, "2026-11-19");
    assert.equal(body.trim(), "Hello");
});

test("inline Markdown escapes text and keeps code literal", () => {
    assert.equal(inline("a <b> & **c** `**d**`"), "a &lt;b&gt; &amp; <strong>c</strong> <code>**d**</code>");
    assert.equal(inline("[Tuned](/blog/)"), '<a href="/blog/">Tuned</a>');
    assert.equal(inline("[x](https://example.com)"), '<a href="https://example.com" rel="noopener">x</a>');
});

test("block Markdown: headings never make a second h1, lists, tables and raw HTML", () => {
    assert.equal(markdown("# Top"), '<h2 id="top">Top</h2>');
    assert.equal(markdown("- a\n- b"), "<ul><li>a</li><li>b</li></ul>");
    assert.equal(markdown("1. a\n2. b"), "<ol><li>a</li><li>b</li></ol>");
    assert.match(markdown("| A | B |\n|---|:-:|\n| 1 | 2 |"), /<th>A<\/th><th style="text-align:center">B<\/th>.*<td>1<\/td>/);
    assert.equal(markdown('<img src="/x.png" alt="">'), '<img src="/x.png" alt="">');
    assert.equal(markdown("one\ntwo\n\nthree"), "<p>one two</p>\n<p>three</p>");
});

test("a post's address is its file's path", () => {
    const p = readPost("vs/other-app.md", "---\ntitle: T\ndescription: D\ndate: 2026-11-19\nkind: compare\n---\nBody");
    assert.equal(p.url, "/blog/vs/other-app/");
    assert.equal(p.folder, "vs");
    assert.throws(() => readPost("x.md", "---\ntitle: T\n---\n"), /description/);
});

test("release notes become a post with only what people see", () => {
    assert.deepEqual(noteLine("* feat: favourites widget by @mjparinas in https://github.com/x/pull/1"), {
        type: "feat",
        text: "Favourites widget",
    });
    assert.equal(noteLine("* ci: faster CI by @mjparinas in https://x"), null);
    assert.equal(noteLine("* feat: admin at /admin with reports by @mjparinas in https://x"), null);
    assert.equal(noteLine("* @dependabot[bot] made their first contribution in https://x"), null);
    const md = releasePost({
        tag: "v1.14.0",
        date: "2026-11-20",
        notes: "* feat: a thing by @a in u\n* fix: a bug by @a in u\n* test: more by @a in u",
    });
    assert.match(md, /^---\ntitle: What's new in Tuned 1.14.0\n/);
    assert.match(md, /## New\n\n- A thing\n\n## Fixed\n\n- A bug\n$/);
    assert.equal(releasePostPath("v1.14.0"), "whats-new/tuned-1-14-0.md");
    assert.equal(releasePostPath("v1.1.0-build.4"), "whats-new/tuned-1-1-0.md");
});

test("every page carries the Android download, and the sitemap lists every post", () => {
    const posts = [
        {
            path: "whats-new/tuned-1-0-0.md",
            text: "---\ntitle: One\ndescription: D\ndate: 2026-11-17\nkind: release\nversion: 1.0.0\n---\nHi",
        },
        { path: "guides/start.md", text: "---\ntitle: Start\ndescription: D\ndate: 2026-11-18\nkind: guide\n---\nHi" },
    ];
    const files = buildSite(SITE, { template: "<!-- @cta -->", groups: [], posts });
    for (const page of [
        "index.html",
        "blog/index.html",
        "blog/guides/start/index.html",
        "blog/whats-new/tuned-1-0-0/index.html",
        "blog/guides/index.html",
    ]) {
        assert.ok(files[page], page);
        assert.ok(files[page].includes(SITE.apk), `${page} links the APK`);
        assert.ok(!/\bGitHub\b|unlocked/i.test(files[page].replace(/https:\/\/github\.com\/[^"]*/g, "")), `${page} names GitHub`);
        assert.ok(files[page].includes(`<link rel="canonical" href="${SITE.url}/`), `${page} has a canonical`);
    }
    for (const url of ["/", "/blog/", "/blog/guides/", "/blog/guides/start/", "/blog/whats-new/tuned-1-0-0/"])
        assert.ok(files["sitemap.xml"].includes(`<loc>${SITE.url}${url}</loc>`), url);
    assert.ok(sitemap(SITE, { posts: [], folders: [] }).startsWith('<?xml version="1.0"'));
});

test("every hand-written page has the shared header and footer", async () => {
    const { readFileSync } = await import("node:fs");
    for (const page of [
        "account/index.html",
        "account/playlists/index.html",
        "tv/index.html",
        "devices/index.html",
        "app/signed-in.html",
        "check/index.html",
    ]) {
        const html = readFileSync(new URL(`../public/${page}`, import.meta.url), "utf8");
        assert.match(html, /<body>\s*<script src="\/chrome.js" data-part="header"><\/script>/, page);
        assert.match(html, /<script src="\/chrome.js" data-part="footer"><\/script>\s*<\/body>/, page);
        assert.ok(html.includes('href="/web.css"'), `${page} loads web.css`);
    }
});

test("no committed page outside /blog/vs/ names GitHub or says everything is unlocked (Melvin, 2026-11-20)", async () => {
    const { readdir, readFile } = await import("node:fs/promises");
    const root = new URL("../public/", import.meta.url);
    const pages = (await readdir(root, { recursive: true })).filter(
        (f) => /\.(html|js|json)$/.test(f) && !f.startsWith("blog/vs/") && !f.startsWith("admin/") && !f.startsWith("check/vendor/"),
    );
    for (const page of pages) {
        const text = (await readFile(new URL(page, root), "utf8")).replace(/https:\/\/github\.com\/[^"'\s)]*/g, "");
        assert.ok(!/\bGitHub\b/.test(text), `${page} names GitHub`);
        assert.ok(!/everything unlocked|has everything|every feature unlocked/i.test(text), `${page} says everything is unlocked`);
    }
});

test("the privacy policy is a page on the site, linked from every footer", async () => {
    const { expected } = await import("../build-site.mjs");
    const files = expected();
    assert.match(files["privacy/index.html"], /<h1>Tuned privacy policy<\/h1>/);
    assert.match(files["privacy/index.html"], /Last updated /);
    assert.ok(files["chrome.js"].includes('href=\\"/privacy/\\"'));
    assert.ok(files["sitemap.xml"].includes("/privacy/</loc>"));
});

test("the landing page's hero has the one download button and no Coming soon badges (Melvin, 2026-11-20)", async () => {
    const { readFile } = await import("node:fs/promises");
    const html = await readFile(new URL("../public/index.html", import.meta.url), "utf8");
    const hero = html.slice(html.indexOf('class="wrap hero"'), html.indexOf('class="facts"'));
    assert.ok(hero.includes('data-cta="apk-hero"'));
    assert.ok(!hero.includes("store-badge"), "no store badges in the hero");
    assert.equal(html.split('class="badges"').length - 1, 1, "the store badges appear once, in Coming soon");
});

test("From the blog shows the chosen posts, then the newest that aren't release notes", async () => {
    const post = (path, kind, date = "2026-11-19") =>
        readPost(path, `---\ntitle: ${path}\ndescription: D\ndate: ${date}\nkind: ${kind}\n---\nBody`);
    const posts = [
        post("whats-new/tuned-9-0-0.md", "release", "2026-12-01"),
        post("guides/a.md", "guide"),
        post("vs/b.md", "compare"),
        post("guides/c.md", "guide"),
    ];
    const paths = (site) => homePosts(site, posts).map((p) => p.path);
    assert.deepEqual(paths({ homePosts: ["guides/c.md", "gone.md"] }), ["guides/c.md", "guides/a.md", "vs/b.md"]);
    assert.deepEqual(paths({}), ["guides/a.md", "guides/c.md", "vs/b.md"]);
    const { existsSync } = await import("node:fs");
    for (const path of SITE.homePosts) assert.ok(existsSync(new URL(`../site/posts/${path}`, import.meta.url)), `${path} exists`);
});

test("the landing page's questions become FAQPage structured data", () => {
    const [ld] = faqJsonLd(
        '<details>\n<summary>Is it free?</summary>\n<p class="muted">Yes. See <a href="/x/">the  guide</a>.</p>\n</details>',
    );
    assert.equal(ld["@type"], "FAQPage");
    assert.deepEqual(ld.mainEntity[0], {
        "@type": "Question",
        name: "Is it free?",
        acceptedAnswer: { "@type": "Answer", text: "Yes. See the guide." },
    });
    assert.deepEqual(faqJsonLd("<p>none</p>"), []);
});

// Search (docs/decisions/website.md › Search): every public page has one h1, its own title and description, a canonical
// address, social cards and breadcrumbs, and every picture has words and a size.
test("every generated page is ready for search", async () => {
    const { expected } = await import("../build-site.mjs");
    const pages = Object.entries(expected()).filter(([path]) => path.endsWith(".html"));
    const titles = new Map();
    for (const [path, html] of pages) {
        assert.equal(html.match(/<h1[\s>]/g)?.length, 1, `${path}: one h1`);
        const title = /<title>([^<]+)<\/title>/.exec(html)?.[1];
        assert.ok(title, `${path}: title`);
        assert.ok(!titles.has(title), `${path}: same title as ${titles.get(title)}`);
        titles.set(title, path);
        const description = /<meta name="description" content="([^"]+)"/.exec(html)?.[1] ?? "";
        assert.ok(description.length >= 50 && description.length <= 200, `${path}: description is ${description.length} characters`);
        for (const tag of [
            'property="og:title"',
            'property="og:image"',
            'property="og:image:alt"',
            'name="twitter:card"',
            'name="twitter:title"',
        ])
            assert.ok(html.includes(tag), `${path}: ${tag}`);
        if (path === "404.html") assert.match(html, /<meta name="robots" content="noindex" \/>/);
        else assert.ok(html.includes('<link rel="canonical" href="https://www.tunedplayer.com/'), `${path}: canonical`);
        if (path.startsWith("blog/")) assert.ok(html.includes('"@type":"BreadcrumbList"'), `${path}: breadcrumbs`);
        for (const img of html.match(/<img\b[^>]*>/g) ?? []) {
            assert.match(img, /\salt="/, `${path}: ${img} needs alt`);
            assert.match(img, /\swidth="\d+"[^>]*\sheight="\d+"|\sheight="\d+"[^>]*\swidth="\d+"/, `${path}: ${img} needs a size`);
        }
        for (const [, json] of html.matchAll(/<script type="application\/ld\+json">([^<]+)<\/script>/g)) JSON.parse(json);
    }
});

test("comparisons get a searchable title, and the name isn't added twice", async () => {
    const { expected } = await import("../build-site.mjs");
    const files = expected();
    assert.match(files["blog/vs/tivimate/index.html"], /<title>Tuned vs TiviMate: features and price compared<\/title>/);
    assert.match(files["blog/guides/buffering/index.html"], /<title>How to fix IPTV buffering in Tuned<\/title>/);
    assert.match(files["blog/guides/buffering/index.html"], /<h1>How to fix buffering in Tuned<\/h1>/);
    assert.match(files["404.html"], /<title>Page not found · Tuned<\/title>/);
});

test("More from the blog links to posts on the same subject, not the same newest three", () => {
    const post = (path, title, kind, extra = {}) => ({ path, title, description: "", kind, date: "2026-11-19", ...extra });
    const xtream = post("guides/xtream.md", "Add an Xtream Codes playlist", "guide");
    const m3u = post("guides/m3u.md", "Add an M3U playlist", "guide");
    const subs = post("guides/subs.md", "Subtitles", "guide");
    const tivimate = post("vs/tivimate.md", "Tuned vs TiviMate", "compare");
    const smarters = post("vs/smarters.md", "Tuned vs IPTV Smarters, Xtream Codes player", "compare");
    const r1 = post("whats-new/1.md", "What's new in 1.0", "release", { version: "1.0.0" });
    const r2 = post("whats-new/2.md", "What's new in 1.1", "release", { version: "1.1.0" });
    const r3 = post("whats-new/3.md", "What's new in 1.2", "release", { version: "1.2.0" });
    const all = [xtream, m3u, subs, tivimate, smarters, r1, r2, r3];
    assert.deepEqual(relatedPosts(xtream, all), [m3u, subs, smarters]);
    assert.deepEqual(relatedPosts(r2, all).slice(0, 2), [r3, r1]);
    assert.ok(!relatedPosts(tivimate, all).some((p) => p.kind === "release"));
});

test("a release's search description names what changed", () => {
    const d = releaseDescription("2.0.1", "Tuned 2.0.1 is out: 2 fixed.", [{ text: "Subtitle timing" }, { text: "The splash screen." }]);
    assert.equal(d, "Tuned 2.0.1, the IPTV player for Android and Android TV, is out. Subtitle timing. The splash screen.");
});
