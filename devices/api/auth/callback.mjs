// GET /api/auth/callback: Google sending the browser back. A browser sign-in gets a session cookie and the account
// page; an app sign-in goes back to the app with a one-time code (the app link /app/signed-in).
import { clearStateCookie, googleFinish, newLink, newSession } from "../../lib/auth.mjs";

export async function GET(request) {
    const url = new URL(request.url);
    try {
        const done = await googleFinish(
            url.origin,
            url.searchParams.get("state"),
            url.searchParams.get("code"),
            request.headers.get("cookie"),
        );
        if (!done) return redirect(`${url.origin}/account?error=failed`);
        if (done.after.to === "app") {
            const link = await newLink(done.account, done.after.challenge);
            return redirect(`${url.origin}/app/signed-in?code=${encodeURIComponent(link)}`);
        }
        // An admin sign-in (lib/admin/api.mjs) comes back to the admin, which then asks for its second step.
        const page =
            done.after.to === "admin"
                ? "/admin"
                : done.after.tv
                  ? `/account?tv=${done.after.tv}`
                  : done.after.then === "check"
                    ? "/check"
                    : done.after.then === "watch"
                      ? "/watch"
                      : "/account";
        return redirect(`${url.origin}${page}`, await newSession(done.account));
    } catch (error) {
        console.error(error);
        return redirect(`${url.origin}/account?error=failed`);
    }
}

/** A redirect that also clears the sign-in's state cookie, and sets the session cookie when there is one. */
function redirect(location, session = null) {
    const headers = new Headers({ Location: location });
    headers.append("Set-Cookie", clearStateCookie());
    if (session) headers.append("Set-Cookie", session);
    return new Response(null, { status: 302, headers });
}
