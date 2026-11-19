// POST /api/stripe/webhook: Stripe's events, checked against STRIPE_WEBHOOK_SECRET. Every change is a save keyed
// by Stripe's own ids, so an event Stripe sends twice changes nothing the second time.
// Subscribe the endpoint to checkout.session.completed, customer.subscription.created, .updated, .deleted and
// charge.refunded.
import { eventChange, signatureValid } from "../../lib/stripe.mjs";
import { recordEvent, markRefunded, saveEntitlement, setStripeCustomer } from "../../lib/account-store.mjs";

export async function POST(request) {
    const payload = await request.text();
    const ok = signatureValid(
        payload,
        request.headers.get("stripe-signature"),
        process.env.STRIPE_WEBHOOK_SECRET,
        Math.floor(Date.now() / 1000),
    );
    if (!ok) return new Response("bad signature", { status: 400 });
    try {
        const event = JSON.parse(payload);
        const change = eventChange(event);
        if (change) {
            if (change.customer) await setStripeCustomer(change.customer.account, change.customer.id);
            if (change.entitlement) await saveEntitlement(change.entitlement);
            if (change.refund) await markRefunded(change.refund.source, change.refund.ref);
            await recordEvent(event.id);
        }
        return new Response("ok");
    } catch (error) {
        // Stripe retries a failed event for three days, so a short outage loses nothing.
        console.error(error);
        return new Response("try again", { status: 500 });
    }
}
