// GET /api/auth/callback: Google sending the browser back. A browser sign-in gets a session cookie and the account
// page; an app sign-in goes back to the app with a one-time code (the app link /app/signed-in).
import { googleFinish, newLink, newSession } from "../../lib/auth.mjs";

export async function GET(request) {
    const url = new URL(request.url);
    try {
        const done = await googleFinish(url.origin, url.searchParams.get("state"), url.searchParams.get("code"));
        if (!done) return Response.redirect(`${url.origin}/account?error=failed`, 302);
        if (done.after.to === "app") {
            const link = await newLink(done.account, done.after.challenge);
            return Response.redirect(`${url.origin}/app/signed-in?code=${encodeURIComponent(link)}`, 302);
        }
        // An admin sign-in (lib/admin/api.mjs) comes back to the admin, which then asks for its second step.
        const page = done.after.to === "admin" ? "/admin" : done.after.tv ? `/account?tv=${done.after.tv}` : "/account";
        return new Response(null, {
            status: 302,
            headers: { Location: `${url.origin}${page}`, "Set-Cookie": await newSession(done.account) },
        });
    } catch (error) {
        console.error(error);
        return Response.redirect(`${url.origin}/account?error=failed`, 302);
    }
}
