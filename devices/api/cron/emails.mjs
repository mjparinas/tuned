// GET /api/cron/emails, every hour from Vercel (vercel.json): the welcome and the daily "Getting to know Tuned" emails
// (docs/decisions/emails.md). Each account gets at most one email a day, about the hour it signed up, and only while
// it hasn't unsubscribed. Does nothing until RESEND_API_KEY, MAIL_FROM, MAIL_POSTAL_ADDRESS and EMAIL_SECRET are set.
import { candidates, recordSends } from "../../lib/email-store.mjs";
import { GAP_MS, SERIES_START, mailReady, nextEmail, readerPlan, sendBatch, seriesMail, unsubscribeUrl } from "../../lib/emails.mjs";
import { SERIES } from "../../lib/email-series.mjs";

export async function GET(request) {
    const secret = process.env.CRON_SECRET;
    if (!secret || request.headers.get("authorization") !== `Bearer ${secret}`) return new Response("Not allowed", { status: 401 });
    const env = process.env;
    if (!mailReady(env)) return Response.json({ status: "not set up" });
    try {
        const now = Date.now();
        const due = [];
        for (const account of await candidates({ since: SERIES_START, total: SERIES.length + 1, gapMs: GAP_MS, now })) {
            const next = nextEmail(account, account.sent, now);
            if (!next) continue;
            const unsubscribe = unsubscribeUrl(account.id, env.EMAIL_SECRET);
            const mail = seriesMail(next, readerPlan(account), { unsubscribeUrl: unsubscribe, postalAddress: env.MAIL_POSTAL_ADDRESS });
            due.push({ account: account.id, to: account.email, key: next.key, mail, unsubscribe });
        }
        let sent = 0;
        for (let i = 0; i < due.length; i += 100) {
            const batch = due.slice(i, i + 100);
            if (await sendBatch(batch, env)) {
                await recordSends(batch);
                sent += batch.length;
            } else console.error(`Resend refused a batch of ${batch.length}`);
        }
        return Response.json({ status: "ok", due: due.length, sent });
    } catch (error) {
        console.error(error);
        return Response.json({ status: "failed" }, { status: 500 });
    }
}
