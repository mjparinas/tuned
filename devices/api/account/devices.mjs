// POST /api/account/devices { remove } or { rename, name }: from the website or any signed-in device. Removing a
// device signs it out and frees its seat; there is no waiting period, since the account owner is signed in.
import { body, caller, guarded, json } from "../../lib/auth.mjs";
import { summary } from "../../lib/account-api.mjs";
import { removeDevice, renameDevice } from "../../lib/account-store.mjs";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function POST(request) {
    return guarded(async () => {
        const who = await caller(request, { changing: true });
        if (!who) return json({ status: "signed out" }, 401);
        const ask = await body(request);
        if (UUID.test(ask?.remove ?? "")) {
            await removeDevice(who.account, ask.remove);
        } else if (UUID.test(ask?.rename ?? "") && typeof ask?.name === "string" && ask.name.trim()) {
            await renameDevice(who.account, ask.rename, ask.name.trim().slice(0, 60));
        } else {
            return json({ status: "bad request" }, 400);
        }
        return json(await summary(who.account, who.device?.id ?? null));
    });
}
