// GET /api/auth/google: starts "Sign in with Google". `?app=<PKCE challenge>` comes from the app and returns to it;
// otherwise the browser comes back to the account page (`?tv=<code>` to sign a TV in after).
import { googleStart, overLimit, signInReady, validChallenge } from "../../lib/auth.mjs";
import { normalizeCode } from "../../lib/tv.mjs";

export async function GET(request) {
    const url = new URL(request.url);
    if (!signInReady()) return Response.redirect(`${url.origin}/account?error=setup`, 302);
    try {
        if (await overLimit(request, "signin", 30)) return Response.redirect(`${url.origin}/account?error=busy`, 302);
        const challenge = url.searchParams.get("app");
        const tv = normalizeCode(url.searchParams.get("tv"));
        const after = challenge ? (validChallenge(challenge) ? { to: "app", challenge } : null) : { to: "web", tv };
        if (!after) return Response.redirect(`${url.origin}/account?error=failed`, 302);
        return Response.redirect(await googleStart(url.origin, after), 302);
    } catch (error) {
        console.error(error);
        return Response.redirect(`${url.origin}/account?error=failed`, 302);
    }
}
