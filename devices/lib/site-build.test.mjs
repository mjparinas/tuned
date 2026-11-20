import { test } from "node:test";
import assert from "node:assert/strict";
import { check } from "../build-site.mjs";
import { SITE } from "../site/config.mjs";
import { buildSite, inline, markdown, noteLine, parseFrontmatter, readPost, releasePost, releasePostPath, sitemap } from "./site-build.mjs";

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
    for (const page of ["account/index.html", "tv/index.html", "devices/index.html", "app/signed-in.html", "check/index.html"]) {
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
