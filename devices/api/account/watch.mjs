// /api/account/watch (docs/decisions/web.md › Web player at /watch): the Premium web player's one-browser lease.
//   GET             whether this signed-in browser may play (Premium, flag) and who holds the lease.
//   POST { browserId, steal? }  take or keep the lease (a second browser takes over by default).
//   DELETE { browserId }        give the lease up when this tab stops.
// The web player doesn't take a device seat. Only the person's own signed-in browser from our own page, while the
// web_player flag is on (preview deployments always). Streams still go straight from the provider to the browser.
import { body, guarded, json, webAccount } from "../../lib/auth.mjs";
import { flagRows } from "../../lib/admin/data.mjs";
import { flagsFor } from "../../lib/admin/flags.mjs";
import { summary } from "../../lib/account-api.mjs";
import { fromOurPage } from "../../lib/library.mjs";
import { command } from "../../lib/redis.mjs";
import { claimLease, holdsLease, LEASE_SECONDS, validBrowserId } from "../../lib/watch-lease.mjs";

async function watchOn() {
    if (process.env.VERCEL_ENV && process.env.VERCEL_ENV !== "production") return true;
    return flagsFor(await flagRows(), null).web_player === true;
}

const keyOf = (account) => `watch:lease:${account}`;

async function gate(request) {
    if (!fromOurPage(request.headers)) return { error: json({ status: "signed out" }, 401) };
    const account = await webAccount(request);
    if (!account) return { error: json({ status: "signed out" }, 401) };
    if (!(await watchOn())) return { error: json({ status: "off" }, 404) };
    const found = await summary(account);
    if (!found) return { error: json({ status: "signed out" }, 401) };
    if (!found.premium) return { error: json({ status: "need premium", premium: null }, 403) };
    return { account, premium: found.premium };
}

export function GET(request) {
    return guarded(async () => {
        const gateResult = await gate(request);
        if (gateResult.error) return gateResult.error;
        const holder = (await command("GET", keyOf(gateResult.account))) ?? null;
        const browserId = validBrowserId(new URL(request.url).searchParams.get("browserId"));
        return json({
            status: "ok",
            premium: gateResult.premium,
            holder,
            yours: holdsLease(holder, browserId),
            leaseSeconds: LEASE_SECONDS,
        });
    });
}

export function POST(request) {
    return guarded(async () => {
        const gateResult = await gate(request);
        if (gateResult.error) return gateResult.error;
        const data = await body(request);
        const steal = data?.steal !== false;
        const current = (await command("GET", keyOf(gateResult.account))) ?? null;
        const claimed = claimLease(current, data?.browserId, { steal });
        if (claimed.error) return json({ status: claimed.error }, 400);
        if (claimed.blocked) {
            return json({ status: "taken", holder: claimed.holder, yours: false, leaseSeconds: LEASE_SECONDS }, 409);
        }
        await command("SET", keyOf(gateResult.account), claimed.holder, "EX", LEASE_SECONDS);
        return json({
            status: "ok",
            holder: claimed.holder,
            yours: true,
            tookOver: claimed.tookOver,
            leaseSeconds: LEASE_SECONDS,
        });
    });
}

export function DELETE(request) {
    return guarded(async () => {
        const gateResult = await gate(request);
        if (gateResult.error) return gateResult.error;
        const data = await body(request);
        const id = validBrowserId(data?.browserId);
        if (!id) return json({ status: "bad request" }, 400);
        const current = (await command("GET", keyOf(gateResult.account))) ?? null;
        if (holdsLease(current, id)) await command("DEL", keyOf(gateResult.account));
        return json({ status: "ok" });
    });
}
