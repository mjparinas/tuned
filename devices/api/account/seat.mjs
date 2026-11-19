// POST /api/account/seat { use, purchases }: a signed-in device asking to use Premium (`use: true`) or giving its
// seat back. It reports the Play purchases it sees, which are checked and saved to the account. The answer is
// "active", "full" (every seat taken, which only then locks the device) or "none" (the account has no Premium),
// with `recorded`: the hashes of the reported purchases the account holds, which the app may then consume.
// The github build has everything unlocked and never holds a seat.
import { seatPlan } from "../../lib/accounts.mjs";
import { body, deviceCaller, guarded, json } from "../../lib/auth.mjs";
import { parsePurchases, recordedPurchases, reportPlay, summary } from "../../lib/account-api.mjs";
import { entitlements, releaseSeat, takeSeat } from "../../lib/account-store.mjs";

export function POST(request) {
    return guarded(async () => {
        const device = await deviceCaller(request);
        if (!device) return json({ status: "signed out" }, 401);
        const ask = await body(request);
        if (ask?.use === false) {
            await releaseSeat(device.account, device.id);
            return json({ ...(await summary(device.account, device.id)), seat: "released" });
        }
        const purchases = parsePurchases(ask?.purchases);
        await reportPlay(device.account, purchases, await entitlements(device.account));
        const owned = await entitlements(device.account);
        const plan = seatPlan(owned, Date.now());
        let seat;
        if (!plan.premium) seat = "none";
        else if (device.build === "github") seat = "active";
        else seat = (await takeSeat(device.account, device.id, plan.limit)) ? "active" : "full";
        return json({ ...(await summary(device.account, device.id)), seat, recorded: recordedPurchases(purchases, owned) });
    });
}
