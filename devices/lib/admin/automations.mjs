// The Automations tab and the hourly job behind it (docs/decisions/admin.md, as Liftsmith's alerts and morning
// digest): emails to ADMIN_EMAILS only, each kind switchable, each at most once every 6 hours (the digest once a day).

import { SITE, tunedMail } from "../emails.mjs";

export const AUTOMATIONS = [
    { key: "purchase", label: "A new purchase", detail: "Premium or seats bought on Play or the website." },
    { key: "refund", label: "A refund", detail: "Its seats go back automatically; this just says so." },
    { key: "report", label: "A problem report", detail: "Someone sent one from Settings › Report a problem." },
    { key: "signin_failures", label: "Failed admin sign-ins", detail: "Five or more wrong second-step codes in a day." },
    { key: "digest", label: "Morning digest", detail: "Yesterday's installs, accounts, purchases, reports and visitors, at 8 AM Eastern." },
];

export const ALERT_GAP_MS = 6 * 3_600_000;
/** 13:00 UTC is 8 AM in Toronto in winter (9 AM in summer). */
export const DIGEST_HOUR_UTC = 13;

/**
 * What the hourly job should send: `happened` since the last run, `failures` (today's wrong codes), the switches,
 * when each kind last went out, and the time. Returns `[{ kind, subject, text }]`.
 */
export function alertsToSend({ happened, failures, digest, switches, last, now }) {
    const out = [];
    const on = (k) => switches[k] !== false;
    const due = (k) => !last[k] || now - last[k] >= ALERT_GAP_MS;
    const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
    // Each alert is a short branded email (lib/emails.mjs tunedMail), with a button to the tab it's about.
    const alert = (kind, subject, m) => {
        const mail = tunedMail({
            kicker: "Tuned admin",
            subject,
            ...m,
            why: "Sent to Tuned's admins. Switch each kind on or off in the admin's Automations tab.",
            footer: null,
        });
        out.push({ kind, subject, text: mail.text, html: mail.html });
    };
    const tab = (name) => `${SITE}/admin?tab=${name}`;
    if (on("purchase") && happened.purchases > 0 && due("purchase"))
        alert("purchase", `Tuned: ${plural(happened.purchases, "new purchase", "new purchases")}`, {
            title: `${plural(happened.purchases, "new purchase", "new purchases")} 🎉`,
            intro: "Someone just bought Premium or more seats, on Play or the website.",
            cta: { label: "Open Revenue", url: tab("Revenue") },
        });
    if (on("refund") && happened.refunds > 0 && due("refund"))
        alert("refund", `Tuned: ${plural(happened.refunds, "refund", "refunds")}`, {
            title: plural(happened.refunds, "refund", "refunds"),
            intro: "Their seats were given back automatically. Nothing to do unless you want a closer look.",
            cta: { label: "Open Revenue", url: tab("Revenue") },
        });
    if (on("report") && happened.reports > 0 && due("report"))
        alert("report", `Tuned: ${plural(happened.reports, "new problem report", "new problem reports")}`, {
            title: plural(happened.reports, "new problem report", "new problem reports"),
            intro: "Sent from Settings › Report a problem, with the device's log (logins already taken out).",
            cta: { label: "Read the reports", url: tab("Reports") },
        });
    if (on("signin_failures") && failures >= 5 && due("signin_failures"))
        alert("signin_failures", `Tuned: ${failures} failed admin sign-ins today`, {
            title: `${failures} failed admin sign-ins today`,
            intro: "Five or more wrong second-step codes since midnight UTC. If that wasn't you, check who's been trying.",
            cta: { label: "Open the Log", url: tab("Log") },
        });
    const hour = new Date(now).getUTCHours();
    const today = new Date(now).toISOString().slice(0, 10);
    const lastDigestDay = last.digest ? new Date(last.digest).toISOString().slice(0, 10) : null;
    if (on("digest") && digest && hour >= DIGEST_HOUR_UTC && lastDigestDay !== today)
        alert("digest", "Tuned: yesterday", {
            title: "Yesterday in Tuned",
            intro: "Good morning. Here's how yesterday went.",
            rows: [
                ["Installs", String(digest.installs)],
                ["New accounts", String(digest.accounts)],
                ["Purchases", String(digest.purchases)],
                ["Problem reports", String(digest.reports)],
                ["Website visitors", String(digest.visitors)],
            ],
            cta: { label: "Open the admin", url: tab("Overview") },
        });
    return out;
}

/** Sends through Resend's API; false (and no throw) when it isn't set up or fails. */
export function resendMailer(env = process.env, fetcher = fetch) {
    return {
        async send(to, subject, text, html) {
            const list = to.map((e) => e.trim()).filter(Boolean);
            if (!env.RESEND_API_KEY || !list.length) return false;
            try {
                const response = await fetcher("https://api.resend.com/emails", {
                    method: "POST",
                    headers: { Authorization: `Bearer ${env.RESEND_API_KEY}`, "Content-Type": "application/json" },
                    body: JSON.stringify({
                        from: env.ADMIN_FROM || "Tuned admin <onboarding@resend.dev>",
                        to: list,
                        subject,
                        text,
                        ...(html ? { html } : {}),
                    }),
                });
                return response.ok;
            } catch (error) {
                console.error(error);
                return false;
            }
        },
    };
}
