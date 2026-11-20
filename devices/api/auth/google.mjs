// GET /api/auth/google: starts "Sign in with Google". `?app=<PKCE challenge>` comes from the app and returns to it;
// otherwise the browser comes back to the account page (`?tv=<code>` to sign a TV in after), or to the provider
// check with `?then=check`.
import { googleRedirect, overLimit, signInReady, validChallenge } from "../../lib/auth.mjs";
import { normalizeCode } from "../../lib/tv.mjs";

export async function GET(request) {
    const url = new URL(request.url);
    if (!signInReady()) return Response.redirect(`${url.origin}/account?error=setup`, 302);
    try {
        if (await overLimit(request, "signin", 30)) return Response.redirect(`${url.origin}/account?error=busy`, 302);
        const challenge = url.searchParams.get("app");
        const tv = normalizeCode(url.searchParams.get("tv"));
        const then = url.searchParams.get("then") === "check" ? "check" : null;
        const after = challenge ? (validChallenge(challenge) ? { to: "app", challenge } : null) : { to: "web", tv, then };
        if (!after) return Response.redirect(`${url.origin}/account?error=failed`, 302);
        return await googleRedirect(url.origin, after);
    } catch (error) {
        console.error(error);
        return Response.redirect(`${url.origin}/account?error=failed`, 302);
    }
}
