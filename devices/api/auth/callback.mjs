// /api/auth/callback: Google (GET) or Apple (POST form_post) sending the browser back. A browser sign-in gets a
// session cookie and the account page; an app sign-in goes back to the app with a one-time code (the app link
// /app/signed-in).
import { appleFinish, clearStateCookie, googleFinish, newLink, newSession } from "../../lib/auth.mjs";

export async function GET(request) {
    const url = new URL(request.url);
    try {
        const done = await googleFinish(
            url.origin,
            url.searchParams.get("state"),
            url.searchParams.get("code"),
            request.headers.get("cookie"),
        );
        return finish(url.origin, done);
    } catch (error) {
        console.error(error);
        return redirect(`${url.origin}/account?error=failed`);
    }
}

/** Apple's authorize uses response_mode=form_post, so the answer arrives as a form POST, not a query redirect. */
export async function POST(request) {
    const url = new URL(request.url);
    try {
        const form = await request.formData();
        if (form.get("error")) return redirect(`${url.origin}/account?error=failed`);
        const done = await appleFinish(url.origin, form.get("state"), form.get("code"), form.get("user"));
        return finish(url.origin, done);
    } catch (error) {
        console.error(error);
        return redirect(`${url.origin}/account?error=failed`);
    }
}

async function finish(origin, done) {
    if (!done) return redirect(`${origin}/account?error=failed`);
    if (done.after.to === "app") {
        const link = await newLink(done.account, done.after.challenge);
        return redirect(`${origin}/app/signed-in?code=${encodeURIComponent(link)}`);
    }
    // An admin sign-in (lib/admin/api.mjs) comes back to the admin, which then asks for its second step.
    const page =
        done.after.to === "admin"
            ? "/admin"
            : done.after.tv
              ? `/account?tv=${done.after.tv}`
              : done.after.then === "check"
                ? "/check"
                : "/account";
    return redirect(`${origin}${page}`, await newSession(done.account));
}

/** A redirect that also clears the sign-in's state cookie, and sets the session cookie when there is one. */
function redirect(location, session = null) {
    const headers = new Headers({ Location: location });
    headers.append("Set-Cookie", clearStateCookie());
    if (session) headers.append("Set-Cookie", session);
    return new Response(null, { status: 302, headers });
}
