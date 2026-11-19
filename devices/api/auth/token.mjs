// POST /api/auth/token { link, verifier?, device, kind, build, name }: a device swapping a one-time link code for
// its own token. A code from an app sign-in needs the app's PKCE verifier; one handed to a TV (sealed by a phone,
// or from the website) doesn't, since only that TV could receive it.
import { newToken, parseDevice, pkceMatches } from "../../lib/accounts.mjs";
import { body, guarded, json, overLimit, takeLink } from "../../lib/auth.mjs";
import { account, signInDevice } from "../../lib/account-store.mjs";

export function POST(request) {
    return guarded(async () => {
        const ask = await body(request);
        const device = parseDevice(ask);
        if (!device) return json({ status: "bad request" }, 400);
        if (await overLimit(request, "token", 30)) return json({ status: "too many" }, 429);
        const link = await takeLink(ask?.link);
        if (!link) return json({ status: "expired" }, 401);
        if (link.challenge && !pkceMatches(ask?.verifier, link.challenge)) return json({ status: "expired" }, 401);
        const token = newToken();
        await signInDevice(link.account, device, token);
        const owner = await account(link.account);
        return json({ status: "ok", token, email: owner?.email ?? "" });
    });
}
