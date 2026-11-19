// POST /api/auth/link: a signed-in phone asking for a one-time code to seal into what it sends a TV, so the TV
// signs in to the same account (docs/decisions/accounts.md). Lasts ten minutes, like the TV's own code.
import { deviceCaller, guarded, json, newLink } from "../../lib/auth.mjs";
import { CODE_SECONDS } from "../../lib/tv.mjs";

export function POST(request) {
    return guarded(async () => {
        const device = await deviceCaller(request);
        if (!device) return json({ status: "signed out" }, 401);
        return json({ status: "ok", link: await newLink(device.account, null, CODE_SECONDS) });
    });
}
