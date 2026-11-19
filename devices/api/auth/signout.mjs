// POST /api/auth/signout: a browser ends its session; a device signs itself out, which frees its seat.
import { deviceCaller, endSession, guarded, json } from "../../lib/auth.mjs";
import { sameSite } from "../../lib/accounts.mjs";
import { removeDevice } from "../../lib/account-store.mjs";

export function POST(request) {
    return guarded(async () => {
        const device = await deviceCaller(request);
        if (device) {
            await removeDevice(device.account, device.id);
            return json({ status: "ok" });
        }
        if (!sameSite(request)) return json({ status: "bad request" }, 400);
        return json({ status: "ok" }, 200, { "Set-Cookie": await endSession(request) });
    });
}
