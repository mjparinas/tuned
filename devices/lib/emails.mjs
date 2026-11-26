// Every email Tuned sends to people (docs/decisions/emails.md): the branded layout, the daily "Getting to know Tuned"
// series after sign-up (email-series.mjs), the one-click unsubscribe, and Resend. The admin's alerts use the same
// layout (lib/admin/automations.mjs). Plain text first, with an HTML twin in Tuned's dark look: the ink background,
// the channel stack, violet for buttons and teal for "Free", so an email looks like the app and the website.
import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import { PREMIUM_PRICE, SERIES, WELCOME } from "./email-series.mjs";

export const SITE = "https://www.tunedplayer.com";

/** Tuned's colours, from site.css's dark theme (ui/theme/Theme.kt). */
export const COLOURS = {
    ink: "#121018",
    card: "#1e1c25",
    card2: "#29262f",
    line: "#34313a",
    text: "#e6e1ea",
    muted: "#cac4d0",
    faint: "#938f99",
    primary: "#c9beff",
    onPrimary: "#2b10a0",
    violet: "#6d4aff",
    teal: "#5fdcc3",
};

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

const TOPIC_COLOUR = { premium: COLOURS.primary, both: COLOURS.primary, tips: "#ffd479", craft: COLOURS.teal, free: COLOURS.teal };

/**
 * One email in Tuned's look. `m`: kicker (small line above the title), title (also the subject unless `subject`),
 * intro, points, badge ("premium" draws the Premium pill), note (a boxed line under the points: the Premium line),
 * dyk ("Did you know?"), cta { label, url }, image { url, alt, href }, why (the first footer line), footer
 * { unsubscribeUrl, postalAddress } (null for admin emails), rows ([label, value] pairs, for the admin's digest).
 */
export function tunedMail(m) {
    const { kicker, title, intro, points = [], badge, note, dyk, cta, image, why, footer, rows = [], subject } = m;
    const foot = [
        why,
        ...(footer
            ? [
                  "Tuned is a player for your own playlists. It doesn't provide any channels or content.",
                  `Unsubscribe: ${footer.unsubscribeUrl}`,
                  `Tuned · ${footer.postalAddress}`,
              ]
            : []),
    ].filter(Boolean);
    const text = [
        ...(kicker ? [kicker.toUpperCase()] : []),
        title,
        "",
        ...(intro ? [intro, ""] : []),
        ...(rows.length ? [...rows.map(([k, v]) => `${k}: ${v}`), ""] : []),
        ...(points.length ? [...points.map((p) => `• ${p}`), ""] : []),
        ...(note ? [note, ""] : []),
        ...(dyk ? [`Did you know? ${dyk}`, ""] : []),
        ...(cta ? [`${cta.label}: ${cta.url}`, ""] : []),
        "--",
        ...foot,
    ].join("\n");

    const c = COLOURS;
    const kickerColour = TOPIC_COLOUR[m.tier] ?? c.primary;
    const pill =
        badge === "premium"
            ? `<span style="display:inline-block;background:${c.violet};background-image:linear-gradient(90deg,#6d4aff,#a24bff);color:#ffffff;font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;padding:4px 10px;border-radius:999px;margin:0 0 10px">&#9733; Premium</span><br>`
            : "";
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><meta name="supported-color-schemes" content="dark"><title>${esc(subject ?? title)}</title></head>
<body style="margin:0;padding:0;background:${c.ink};color:${c.text};font-family:Roboto,-apple-system,'Segoe UI',Helvetica,Arial,sans-serif">
<div style="display:none;max-height:0;overflow:hidden;opacity:0">${esc(intro ?? "")}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${c.ink}"><tr><td align="center" style="padding:28px 16px 36px">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px">
<tr><td style="padding:0 4px 20px"><table role="presentation" cellpadding="0" cellspacing="0"><tr>
<td style="padding-right:10px"><img src="${SITE}/apple-touch-icon.png" width="36" height="36" alt="" style="display:block;border-radius:10px"></td>
<td style="color:${c.text};font-weight:700;font-size:19px;letter-spacing:-.01em">Tuned</td></tr></table></td></tr>
<tr><td style="background:${c.card};border:1px solid ${c.line};border-radius:20px;padding:26px 24px 8px">
${kicker ? `<p style="margin:0 0 10px;font-size:12px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:${kickerColour}">${esc(kicker)}</p>` : ""}${pill}
<h1 style="margin:0 0 14px;font-size:24px;line-height:1.25;font-weight:700;color:${c.text}">${esc(title)}</h1>
${intro ? `<p style="margin:0 0 16px;font-size:16px;line-height:1.55;color:${c.text}">${esc(intro)}</p>` : ""}
${rows.length ? `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin:0 0 16px">${rows.map(([k, v]) => `<tr><td style="padding:9px 0;border-bottom:1px solid ${c.line};font-size:15px;color:${c.muted}">${esc(k)}</td><td align="right" style="padding:9px 0;border-bottom:1px solid ${c.line};font-size:17px;font-weight:700;color:${c.text}">${esc(v)}</td></tr>`).join("")}</table>` : ""}
${points.length ? `<table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 0 12px">${points.map((p) => `<tr><td valign="top" style="padding:3px 10px 9px 0;color:${c.primary};font-size:16px;line-height:1.5">&#9656;</td><td style="padding:3px 0 9px;font-size:16px;line-height:1.5;color:${c.text}">${esc(p)}</td></tr>`).join("")}</table>` : ""}
${note ? `<p style="margin:0 0 16px;padding:12px 14px;border-radius:14px;background:${c.card2};border-left:3px solid ${c.violet};font-size:15px;line-height:1.5;color:${c.text}">${esc(note)}</p>` : ""}
${dyk ? `<p style="margin:0 0 16px;padding:12px 14px;border-radius:14px;background:${c.card2};font-size:15px;line-height:1.5;color:${c.muted}"><strong style="color:${c.teal}">Did you know?</strong> ${esc(dyk)}</p>` : ""}
${cta ? `<p style="margin:20px 0 22px"><a href="${esc(cta.url)}" style="display:inline-block;background:${c.primary};color:${c.onPrimary};text-decoration:none;font-weight:700;font-size:16px;padding:14px 24px;border-radius:999px">${esc(cta.label)}</a></p>` : ""}
</td></tr>
${image ? `<tr><td style="padding:16px 0 0"><a href="${esc(image.href ?? cta?.url ?? SITE)}"><img src="${esc(image.url)}" width="560" alt="${esc(image.alt)}" style="display:block;width:100%;max-width:560px;height:auto;border-radius:20px;border:1px solid ${c.line}"></a></td></tr>` : ""}
<tr><td style="padding:22px 6px 0">
${foot
    .map((l) =>
        footer && l.startsWith("Unsubscribe: ")
            ? `<p style="margin:0 0 6px;font-size:12px;line-height:1.5;color:${c.faint}"><a href="${esc(footer.unsubscribeUrl)}" style="color:${c.faint};text-decoration:underline">Unsubscribe from these emails</a></p>`
            : `<p style="margin:0 0 6px;font-size:12px;line-height:1.5;color:${c.faint}">${esc(l)}</p>`,
    )
    .join("\n")}
</td></tr></table></td></tr></table></body></html>`;
    return { subject: subject ?? title, text, html };
}

// ---- Who the reader is -------------------------------------------------------------------------------------------

/**
 * "premium" (the account has Premium), "unlocked" (one of its devices runs the copy with everything unlocked) or
 * "free". From what the server already knows: `premium` from seatPlan, and the builds of its devices.
 */
export function readerPlan({ premium, builds = [] }) {
    if (premium) return "premium";
    if (builds.includes("github")) return "unlocked";
    return "free";
}

/** The boxed line on a Premium email, for this reader. Free readers hear the price; others that they have it. */
export function premiumNote(tier, plan) {
    if (tier !== "premium" && tier !== "both") return null;
    if (plan === "premium") return "You have Premium, so this is already yours. Enjoy it.";
    if (plan === "unlocked") return "Your copy of Tuned already has this unlocked.";
    const what = tier === "both" ? "The Premium parts come with" : "Only in";
    return `${what} Tuned Premium: ${PREMIUM_PRICE}, for up to 3 devices. No ads either way.`;
}

// ---- The series --------------------------------------------------------------------------------------------------

/** Accounts made before this don't get the series (it's for new sign-ups; Melvin, 2026-11-26). */
export const SERIES_START = Date.parse("2026-11-27T00:00:00Z");
/** The welcome only goes out within two days of sign-up; a later start goes straight to the first tip. */
export const WELCOME_WINDOW_MS = 2 * 86_400_000;
/** Each next email waits this long after the last, so it lands about the same hour each day. */
export const GAP_MS = 23 * 3_600_000;

/**
 * The next email for one account, or null. `account` { createdAt }, `sent` [{ key, at }] (keys are "welcome" and
 * the series' ids), `now`. Welcome first (only while the account is new), then the series in order, one a day.
 */
export function nextEmail(account, sent, now) {
    if (account.createdAt < SERIES_START) return null;
    const done = new Set(sent.map((s) => s.key));
    const last = Math.max(0, ...sent.map((s) => s.at));
    if (last && now - last < GAP_MS) return null;
    if (!sent.length && now - account.createdAt < WELCOME_WINDOW_MS) return { key: "welcome", item: WELCOME, n: 0 };
    const n = SERIES.findIndex((s) => !done.has(s.id));
    if (n < 0) return null;
    return { key: SERIES[n].id, item: SERIES[n], n: n + 1 };
}

const TIER_LABEL = { free: "Free", both: "Free + Premium", premium: "Premium", craft: "Built with care", tips: "Did you know?" };

/** The email for one step of the series, for this reader. */
export function seriesMail({ item, n }, plan, footer) {
    const total = SERIES.length;
    const url = (path) => `${SITE}${path}`;
    if (item.tier === "welcome")
        return tunedMail({
            tier: "free",
            kicker: "Getting to know Tuned",
            title: item.title,
            subject: "Welcome to Tuned 👋",
            intro: item.intro,
            points: item.points,
            cta: { label: item.cta.label, url: url(item.cta.path) },
            image: { url: `${SITE}/email/${item.image}.jpg`, alt: "Tuned on a phone and a TV" },
            why: "You're getting this because you made a Tuned account with this address.",
            footer,
        });
    const premiumCta = item.tier === "premium" && plan === "free";
    return tunedMail({
        tier: item.tier,
        kicker: `Day ${n} of ${total} · ${item.topic}`,
        badge: item.tier === "premium" ? "premium" : undefined,
        title: item.title,
        intro: item.intro,
        points: item.points,
        note: premiumNote(item.tier, plan),
        dyk: item.dyk,
        cta: premiumCta ? { label: "Get Tuned Premium", url: url("/account") } : { label: item.cta.label, url: url(item.cta.path) },
        image: { url: `${SITE}/email/${item.image}.jpg`, alt: item.title, href: url(item.cta.path) },
        why: `You're getting this because you made a Tuned account with this address. This is day ${n} of ${total} of Getting to know Tuned (${TIER_LABEL[item.tier]}).`,
        footer,
    });
}

// ---- Unsubscribe -------------------------------------------------------------------------------------------------

/** An HMAC of the account id, so the link needs nothing stored and can't be guessed. */
export function unsubscribeToken(accountId, secret) {
    return createHmac("sha256", secret).update(`unsubscribe:${accountId}`).digest("base64url").slice(0, 32);
}

export function checkUnsubscribe(accountId, token, secret) {
    if (!secret || typeof accountId !== "string" || typeof token !== "string") return false;
    const want = Buffer.from(unsubscribeToken(accountId, secret));
    const got = Buffer.from(token);
    return want.length === got.length && timingSafeEqual(want, got);
}

export const unsubscribeUrl = (accountId, secret) =>
    `${SITE}/api/email/unsubscribe?a=${encodeURIComponent(accountId)}&t=${unsubscribeToken(accountId, secret)}`;

// ---- Sending -----------------------------------------------------------------------------------------------------

/** What sending people email needs; the cron does nothing without all of it (CAN-SPAM and CASL need the address). */
export const mailReady = (env) => Boolean(env.RESEND_API_KEY && env.MAIL_FROM && env.MAIL_POSTAL_ADDRESS && env.EMAIL_SECRET);

/**
 * Sends up to 100 emails in one Resend call (its batch API, so a run is a request or two, not one per person).
 * `emails` [{ to, mail, unsubscribe, key }]. Returns true when Resend took them.
 */
export async function sendBatch(emails, env = process.env, fetcher = fetch) {
    if (!emails.length) return true;
    const body = emails.map((e) => ({
        from: env.MAIL_FROM,
        to: [e.to],
        subject: e.mail.subject,
        html: e.mail.html,
        text: e.mail.text,
        headers: { "List-Unsubscribe": `<${e.unsubscribe}>`, "List-Unsubscribe-Post": "List-Unsubscribe=One-Click" },
        tags: [{ name: "series", value: e.key.replace(/[^a-zA-Z0-9_-]/g, "_") }],
    }));
    const response = await fetcher("https://api.resend.com/emails/batch", {
        method: "POST",
        headers: {
            Authorization: `Bearer ${env.RESEND_API_KEY}`,
            "Content-Type": "application/json",
            "Idempotency-Key": `tuned-series-${createHash("sha256")
                .update(emails.map((e) => `${e.account}:${e.key}`).join(","))
                .digest("hex")}`,
        },
        body: JSON.stringify(body),
    });
    return response.ok;
}

/** The page an unsubscribe link opens: one POST button, so mail scanners that open links can't unsubscribe anyone. */
export function unsubscribePage({ title, body, form }) {
    const c = COLOURS;
    const fields = form
        ? Object.entries(form.fields)
              .map(([k, v]) => `<input type="hidden" name="${esc(k)}" value="${esc(v)}">`)
              .join("")
        : "";
    return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex"><title>${esc(title)} · Tuned</title></head>
<body style="margin:0;background:${c.ink};color:${c.text};font-family:Roboto,-apple-system,'Segoe UI',Arial,sans-serif"><main style="max-width:480px;margin:0 auto;padding:48px 20px">
<p style="display:flex;align-items:center;gap:10px;font-weight:700;font-size:19px;margin:0 0 28px"><img src="/apple-touch-icon.png" width="36" height="36" alt="" style="border-radius:10px">Tuned</p>
<h1 style="font-size:26px;margin:0 0 12px">${esc(title)}</h1><p style="font-size:16px;line-height:1.55;color:${c.muted}">${esc(body)}</p>
${form ? `<form method="post" action="${esc(form.action)}">${fields}<button type="submit" style="min-height:48px;padding:0 24px;border:0;border-radius:999px;background:${c.primary};color:${c.onPrimary};font-size:16px;font-weight:700;cursor:pointer">${esc(form.button)}</button></form>` : `<p><a href="/account" style="color:${c.primary}">Your Tuned account</a></p>`}
</main></body></html>`;
}
