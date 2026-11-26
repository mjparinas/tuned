// /api/email/unsubscribe?a=<account>&t=<token>: the link at the foot of every email (docs/decisions/emails.md).
// GET shows a page with one button (a POST), so mail scanners that open links can't unsubscribe anyone. POST stops
// the daily emails: from that button, or from a mail app's own Unsubscribe (RFC 8058 one-click, which POSTs
// "List-Unsubscribe=One-Click" to this same address). The token is an HMAC of the account id, so nothing is stored.
import { checkUnsubscribe, unsubscribePage } from "../../lib/emails.mjs";
import { setTips } from "../../lib/email-store.mjs";

const html = (body, status = 200) =>
    new Response(body, { status, headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });

const invalid = () =>
    html(
        unsubscribePage({
            title: "That link didn't work",
            body: "It may have been cut short by your mail app. You can turn the emails off from your Tuned account instead.",
        }),
        400,
    );

export function GET(request) {
    const url = new URL(request.url);
    const a = url.searchParams.get("a");
    const t = url.searchParams.get("t");
    if (!checkUnsubscribe(a, t, process.env.EMAIL_SECRET)) return invalid();
    return html(
        unsubscribePage({
            title: "Stop the daily Tuned emails?",
            body: "You'll stop getting Getting to know Tuned. You can turn them back on from your Tuned account any time.",
            form: {
                action: `/api/email/unsubscribe?a=${encodeURIComponent(a)}&t=${encodeURIComponent(t)}`,
                fields: {},
                button: "Unsubscribe",
            },
        }),
    );
}

export async function POST(request) {
    const url = new URL(request.url);
    const a = url.searchParams.get("a");
    const t = url.searchParams.get("t");
    if (!checkUnsubscribe(a, t, process.env.EMAIL_SECRET)) return invalid();
    try {
        await setTips(a, false);
    } catch (error) {
        // An account deleted since the email went out has nothing left to unsubscribe.
        console.error(error);
    }
    return html(
        unsubscribePage({
            title: "You're unsubscribed",
            body: "No more daily Tuned emails. If you change your mind, turn them back on from your Tuned account.",
        }),
    );
}
