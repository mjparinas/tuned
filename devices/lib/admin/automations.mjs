// The Automations tab and the daily job behind it (docs/decisions/admin.md, as Liftsmith's alerts and morning
// digest): emails to ADMIN_EMAILS only, each kind switchable, each at most once every 6 hours (the digest once a day).

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
 * What the daily job should send: `happened` since the last run, `failures` (today's wrong codes), the switches,
 * when each kind last went out, and the time. Returns `[{ kind, subject, text }]`.
 */
export function alertsToSend({ happened, failures, digest, switches, last, now }) {
    const out = [];
    const on = (k) => switches[k] !== false;
    const due = (k) => !last[k] || now - last[k] >= ALERT_GAP_MS;
    const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
    if (on("purchase") && happened.purchases > 0 && due("purchase"))
        out.push({
            kind: "purchase",
            subject: `Tuned: ${plural(happened.purchases, "new purchase", "new purchases")}`,
            text: "See the Revenue tab.",
        });
    if (on("refund") && happened.refunds > 0 && due("refund"))
        out.push({
            kind: "refund",
            subject: `Tuned: ${plural(happened.refunds, "refund", "refunds")}`,
            text: "Their seats were given back. See the Revenue tab.",
        });
    if (on("report") && happened.reports > 0 && due("report"))
        out.push({
            kind: "report",
            subject: `Tuned: ${plural(happened.reports, "new problem report", "new problem reports")}`,
            text: "See the Reports tab.",
        });
    if (on("signin_failures") && failures >= 5 && due("signin_failures"))
        out.push({
            kind: "signin_failures",
            subject: `Tuned: ${failures} failed admin sign-ins today`,
            text: "If that wasn't you, check the Log tab.",
        });
    const hour = new Date(now).getUTCHours();
    const today = new Date(now).toISOString().slice(0, 10);
    const lastDigestDay = last.digest ? new Date(last.digest).toISOString().slice(0, 10) : null;
    if (on("digest") && digest && hour >= DIGEST_HOUR_UTC && lastDigestDay !== today)
        out.push({
            kind: "digest",
            subject: "Tuned: yesterday",
            text: [
                `Installs: ${digest.installs}`,
                `New accounts: ${digest.accounts}`,
                `Purchases: ${digest.purchases}`,
                `Problem reports: ${digest.reports}`,
                `Website visitors: ${digest.visitors}`,
            ].join("\n"),
        });
    return out;
}

/** Sends through Resend's API; false (and no throw) when it isn't set up or fails. */
export function resendMailer(env = process.env, fetcher = fetch) {
    return {
        async send(to, subject, text) {
            const list = to.map((e) => e.trim()).filter(Boolean);
            if (!env.RESEND_API_KEY || !list.length) return false;
            try {
                const response = await fetcher("https://api.resend.com/emails", {
                    method: "POST",
                    headers: { Authorization: `Bearer ${env.RESEND_API_KEY}`, "Content-Type": "application/json" },
                    body: JSON.stringify({ from: env.ADMIN_FROM || "Tuned admin <onboarding@resend.dev>", to: list, subject, text }),
                });
                return response.ok;
            } catch (error) {
                console.error(error);
                return false;
            }
        },
    };
}
