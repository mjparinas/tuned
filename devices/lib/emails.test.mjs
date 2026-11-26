import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { SERIES, WELCOME } from "./email-series.mjs";
import {
    GAP_MS,
    SERIES_START,
    checkUnsubscribe,
    mailReady,
    nextEmail,
    premiumNote,
    readerPlan,
    sendBatch,
    seriesMail,
    tunedMail,
    unsubscribeToken,
    unsubscribeUrl,
} from "./emails.mjs";

const DAY = 86_400_000;
const made = SERIES_START + 5 * DAY;
const footer = { unsubscribeUrl: "https://www.tunedplayer.com/api/email/unsubscribe?a=x&t=y", postalAddress: "1 Example St, Vancouver BC" };

test("the welcome goes first, then one series email a day in order", () => {
    assert.equal(nextEmail({ createdAt: made }, [], made + 3_600_000).key, "welcome");
    const sent = [{ key: "welcome", at: made + 3_600_000 }];
    assert.equal(nextEmail({ createdAt: made }, sent, made + 10 * 3_600_000), null, "not twice in a day");
    const first = nextEmail({ createdAt: made }, sent, made + 3_600_000 + GAP_MS);
    assert.equal(first.key, SERIES[0].id);
    assert.equal(first.n, 1);
    const later = [...sent, { key: SERIES[0].id, at: made + DAY }, { key: SERIES[1].id, at: made + 2 * DAY }];
    assert.equal(nextEmail({ createdAt: made }, later, made + 3 * DAY).key, SERIES[2].id);
});

test("an account found late skips the welcome, and the series ends", () => {
    assert.equal(nextEmail({ createdAt: made }, [], made + 3 * DAY).key, SERIES[0].id);
    const all = [WELCOME, ...SERIES].map((s, i) => ({ key: s.id, at: made + i * DAY }));
    assert.equal(nextEmail({ createdAt: made }, all, made + 100 * DAY), null);
});

test("accounts made before the series started get nothing", () => {
    assert.equal(nextEmail({ createdAt: SERIES_START - DAY }, [], SERIES_START), null);
});

test("the Premium line fits the reader", () => {
    assert.equal(readerPlan({ premium: "yearly", builds: ["play"] }), "premium");
    assert.equal(readerPlan({ premium: null, builds: ["github"] }), "unlocked");
    assert.equal(readerPlan({ premium: null, builds: [] }), "free");
    assert.match(premiumNote("premium", "free"), /US\$9\.99 a year or US\$24\.99 once/);
    assert.match(premiumNote("premium", "free"), /3 devices/);
    assert.match(premiumNote("premium", "premium"), /already yours/);
    assert.match(premiumNote("both", "unlocked"), /unlocked/);
    assert.equal(premiumNote("free", "free"), null);
    assert.equal(premiumNote("craft", "free"), null);
});

test("a Premium email shows the badge, and a free reader gets a button to buy it", () => {
    const item = SERIES.find((s) => s.tier === "premium");
    const n = SERIES.indexOf(item) + 1;
    const free = seriesMail({ item, n }, "free", footer);
    assert.match(free.html, /&#9733; Premium/);
    assert.match(free.html, /Get Tuned Premium/);
    assert.match(free.text, new RegExp(`Day ${n} of ${SERIES.length}`, "i"));
    const paid = seriesMail({ item, n }, "premium", footer);
    assert.doesNotMatch(paid.html, /Get Tuned Premium/);
    assert.match(paid.html, /already yours/);
});

test("every email says Tuned provides no content, and carries the unsubscribe link and postal address", () => {
    for (const [i, item] of [WELCOME, ...SERIES].entries()) {
        const mail = seriesMail({ item, n: i }, "free", footer);
        for (const part of [mail.text, mail.html]) {
            assert.match(part, /doesn(?:'|&#39;)t provide any channels or content/);
            assert.match(part, /1 Example St, Vancouver BC/);
        }
        assert.match(mail.text, /Unsubscribe: https:\/\/www\.tunedplayer\.com\/api\/email\/unsubscribe/);
        assert.match(mail.html, /Unsubscribe from these emails/);
    }
});

test("the layout escapes what it's given", () => {
    const mail = tunedMail({ title: "<b>&", intro: '"x"', why: "w", footer: null });
    assert.match(mail.html, /&lt;b&gt;&amp;/);
    assert.doesNotMatch(mail.html, /<b>&/);
});

test("each series email has a unique id, a picture, a link and a few points", () => {
    const ids = new Set();
    for (const item of [WELCOME, ...SERIES]) {
        assert.ok(!ids.has(item.id), `${item.id} repeats`);
        ids.add(item.id);
        assert.ok(item.title.length <= 60, `${item.id}: subject too long`);
        assert.ok(item.points.length >= 3 && item.points.length <= 6, `${item.id}: 3 to 6 points`);
        assert.ok(item.cta.path.startsWith("/"), item.id);
        const image = fileURLToPath(new URL(`../public/email/${item.image}.jpg`, import.meta.url));
        assert.ok(existsSync(image), `public/email/${item.image}.jpg is missing (node devices/email-images.mjs)`);
    }
    assert.ok(SERIES.length >= 30, "about a month of emails");
    const premium = SERIES.filter((s) => s.tier === "premium" || s.tier === "both").length;
    assert.ok(premium / SERIES.length >= 0.25, "a good share are about Premium");
});

test("the words keep Tuned's rules", () => {
    // Never a provider, channel or sports brand, never GitHub (website.md), never a sleep timer or a second stream on
    // screen (scope.md), Canadian spelling.
    const banned = [
        /github/i,
        /sleep timer/i,
        /multi-?view/i,
        /picture[- ]in[- ]picture.*second/i,
        /\bcolor/i,
        /\bfavorite/i,
        /\bcenter\b/i,
    ];
    const brands = /\b(ESPN|DAZN|Sky|NHL|NBA|NFL|UFC|CBC|CTV|TSN|BBC|HBO|Netflix|TiviMate|IPTV Smarters)\b/;
    for (const item of [WELCOME, ...SERIES]) {
        const words = [item.title, item.intro, ...item.points, item.dyk ?? ""].join(" ");
        for (const rule of banned) assert.doesNotMatch(words, rule, `${item.id}: ${rule}`);
        assert.doesNotMatch(words, brands, item.id);
    }
});

test("unsubscribe links are signed and checked", () => {
    const secret = "s".repeat(40);
    const token = unsubscribeToken("acct-1", secret);
    assert.ok(checkUnsubscribe("acct-1", token, secret));
    assert.ok(!checkUnsubscribe("acct-2", token, secret));
    assert.ok(!checkUnsubscribe("acct-1", token, "other"));
    assert.ok(!checkUnsubscribe("acct-1", token, ""));
    assert.ok(!checkUnsubscribe("acct-1", null, secret));
    assert.match(unsubscribeUrl("acct-1", secret), new RegExp(`a=acct-1&t=${token}$`));
});

test("nothing is sent until sending is fully set up", () => {
    const full = { RESEND_API_KEY: "k", MAIL_FROM: "Tuned <hi@tunedplayer.com>", MAIL_POSTAL_ADDRESS: "x", EMAIL_SECRET: "y" };
    assert.ok(mailReady(full));
    for (const key of Object.keys(full)) assert.ok(!mailReady({ ...full, [key]: "" }), key);
});

test("a batch goes to Resend with one-click unsubscribe headers", async () => {
    let seen;
    const fetcher = async (url, init) => {
        seen = { url, init };
        return { ok: true };
    };
    const mail = seriesMail({ item: SERIES[0], n: 1 }, "free", footer);
    const ok = await sendBatch(
        [{ account: "a1", to: "a@example.com", key: SERIES[0].id, mail, unsubscribe: footer.unsubscribeUrl }],
        { RESEND_API_KEY: "k", MAIL_FROM: "Tuned <hi@tunedplayer.com>" },
        fetcher,
    );
    assert.ok(ok);
    assert.equal(seen.url, "https://api.resend.com/emails/batch");
    assert.match(seen.init.headers["Idempotency-Key"], /^tuned-series-[0-9a-f]{64}$/);
    const [one] = JSON.parse(seen.init.body);
    assert.equal(one.headers["List-Unsubscribe-Post"], "List-Unsubscribe=One-Click");
    assert.equal(one.headers["List-Unsubscribe"], `<${footer.unsubscribeUrl}>`);
    assert.deepEqual(one.to, ["a@example.com"]);
});
