// Every email Tuned sends, written out as HTML files to look at (docs/decisions/emails.md):
//   node devices/email-preview.mjs <folder> [free|premium|unlocked]
// The pictures and icon point at devices/public, so the files open offline. Made-up footer; nothing is sent.
import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { SERIES, WELCOME } from "./lib/email-series.mjs";
import { SITE, seriesMail } from "./lib/emails.mjs";
import { alertsToSend } from "./lib/admin/automations.mjs";

const folder = resolve(process.argv[2] ?? "email-preview");
const plan = process.argv[3] ?? "free";
const pub = fileURLToPath(new URL("./public", import.meta.url));
const local = (html) =>
    html
        .replaceAll(`${SITE}/email/`, `file://${pub}/email/`)
        .replaceAll(`${SITE}/apple-touch-icon.png`, `file://${pub}/apple-touch-icon.png`);
const footer = { unsubscribeUrl: `${SITE}/api/email/unsubscribe?a=preview&t=preview`, postalAddress: "123 Example Street, Vancouver, BC" };
mkdirSync(folder, { recursive: true });
const pad = (n) => String(n).padStart(2, "0");
writeFileSync(join(folder, "00-welcome.html"), local(seriesMail({ item: WELCOME, n: 0 }, plan, footer).html));
SERIES.forEach((item, i) =>
    writeFileSync(join(folder, `${pad(i + 1)}-${item.id}.html`), local(seriesMail({ item, n: i + 1 }, plan, footer).html)),
);
const now = Date.parse("2026-11-26T14:00:00Z");
const alerts = alertsToSend({
    happened: { purchases: 2, refunds: 1, reports: 3 },
    failures: 6,
    digest: { installs: 41, accounts: 9, purchases: 2, reports: 3, visitors: 812 },
    switches: {},
    last: {},
    now,
});
for (const a of alerts) writeFileSync(join(folder, `admin-${a.kind}.html`), local(a.html));
console.log(`Wrote ${SERIES.length + 1 + alerts.length} emails to ${folder}`);
