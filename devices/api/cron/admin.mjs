// GET /api/cron/admin, every hour from Vercel (vercel.json): alerts, the morning digest, and pruning old rows.
// Vercel sends CRON_SECRET as a bearer token; anything else is refused.
import * as data from "../../lib/admin/data.mjs";
import { command } from "../../lib/redis.mjs";
import { alertsToSend, resendMailer } from "../../lib/admin/automations.mjs";

export async function GET(request) {
    const secret = process.env.CRON_SECRET;
    if (!secret || request.headers.get("authorization") !== `Bearer ${secret}`) return new Response("Not allowed", { status: 401 });
    try {
        const now = Date.now();
        const last = (await data.getSetting("alert_last")) ?? {};
        const lastRun = (await data.getSetting("cron_last_run")) ?? now - 3_600_000;
        const alerts = alertsToSend({
            happened: await data.happenedSince(lastRun),
            failures: Number((await command("GET", `adminfail:${new Date(now).toISOString().slice(0, 10)}`)) ?? 0),
            digest: await data.digest(),
            switches: (await data.getSetting("automations")) ?? {},
            last,
            now,
        });
        const mail = resendMailer();
        const to = String(process.env.ADMIN_EMAILS ?? "").split(",");
        for (const alert of alerts) if (await mail.send(to, alert.subject, alert.text, alert.html)) last[alert.kind] = now;
        await data.setSetting("alert_last", last);
        await data.setSetting("cron_last_run", now);
        if (new Date(now).getUTCHours() === 4) await data.prune();
        return Response.json({ status: "ok", sent: alerts.map((a) => a.kind) });
    } catch (error) {
        console.error(error);
        return Response.json({ status: "failed" }, { status: 500 });
    }
}
