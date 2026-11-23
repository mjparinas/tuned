// GET /api/auth/google: starts "Sign in with Google". `?app=<PKCE challenge>` comes from the app and returns to it;
// otherwise the browser comes back to the account page (`?tv=<code>` to sign a TV in after), the provider check with
// `?then=check`, or the web player with `?then=watch`.
import { googleRedirect, overLimit, signInReady, validChallenge } from "../../lib/auth.mjs";
import { normalizeCode } from "../../lib/tv.mjs";

const THEN = new Set(["check", "watch"]);

export async function GET(request) {
    const url = new URL(request.url);
    if (!signInReady()) return Response.redirect(`${url.origin}/account?error=setup`, 302);
    try {
        if (await overLimit(request, "signin", 30)) return Response.redirect(`${url.origin}/account?error=busy`, 302);
        const challenge = url.searchParams.get("app");
        const tv = normalizeCode(url.searchParams.get("tv"));
        const thenParam = url.searchParams.get("then");
        const then = THEN.has(thenParam) ? thenParam : null;
        const after = challenge ? (validChallenge(challenge) ? { to: "app", challenge } : null) : { to: "web", tv, then };
        if (!after) return Response.redirect(`${url.origin}/account?error=failed`, 302);
        return await googleRedirect(url.origin, after);
    } catch (error) {
        console.error(error);
    }
    return Response.redirect(`${url.origin}/account?error=failed`, 302);
}
