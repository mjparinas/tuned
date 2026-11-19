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
        assert.ok(files[page].includes(`<link rel="canonical" href="${SITE.url}/`), `${page} has a canonical`);
    }
    for (const url of ["/", "/blog/", "/blog/guides/", "/blog/guides/start/", "/blog/whats-new/tuned-1-0-0/"])
        assert.ok(files["sitemap.xml"].includes(`<loc>${SITE.url}${url}</loc>`), url);
    assert.ok(sitemap(SITE, { posts: [], folders: [] }).startsWith('<?xml version="1.0"'));
});
