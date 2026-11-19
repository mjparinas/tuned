# Accounts, seats and backups

Melvin, 2026-11-19: a website "with registration, validates number of devices, accepts additional payments for more seats, and stores device backups, and qr codes for set up", replacing Google Drive ("i want to completely deprecate google drive sync"). The plan is `tuned/tuned-accounts-plan.md` in the project files. His answers the same day: accounts are optional, backups aren't encrypted ("i dont want to encrypt backups"), no Drive migration ("i dont have any customers"), the website sells through Stripe, the `github` build gets accounts and backups free with no seat limit, Google is the only sign-in, extra seats come both once and yearly ("do a and b"), and they can't be mixed ("make it so you can't mix lifetime account and yearly seats"). The site is `https://www.tunedplayer.com` (see [Domain](#domain)).

**Built:** the server (`devices/`) and, in the app, signing in, the backup in place of Google Drive sync, Drive removed ([sync](sync.md)), seats per account, Play's seat products, and Send to a TV carrying the account.

## Where things live

The phone and TV keep SQLite (Room): Turso was measured against the app's own database and was about 6 times slower to open Live TV, took 22 minutes instead of 11 seconds to import a large playlist, and couldn't search parts of words. The server is the existing Vercel project `tuned`:

- **Postgres on Neon** (`devices/schema.sql`, `lib/db.mjs`, `lib/account-store.mjs`): accounts, their Google sign-ins, devices, purchases (`entitlements`, from Play or Stripe), backups. Neon's HTTP driver (`@neondatabase/serverless`) is the project's one dependency, loaded only when a request needs the database, so the tests need nothing installed. The schema is run by hand once in Neon's SQL editor and is safe to run again.
- **Upstash Redis** (already there): website sessions, the one-time codes that sign a device in, sign-in state, rate limits, and the TV relay.
- **Backups are in Postgres too**, gzipped, rather than in Vercel Blob as first planned: a backup is well under 1 MB, and keeping it beside the account makes "only save if nobody else saved since" one transaction, with no file address that could leak. If backups grow, they can move to Blob without changing the app.

## Signing in

- **Google only** (OpenID Connect with PKCE, state and nonce; `lib/auth.mjs`, `/api/auth/google`, `/api/auth/callback`), with the website's own OAuth client (`GOOGLE_WEB_CLIENT_ID`, `GOOGLE_WEB_CLIENT_SECRET`). The first sign-in makes the account. We store the email and name Google gives, nothing else from Google.
- **The website** keeps a 30-day session cookie (HttpOnly, Secure, SameSite=Lax), stored hashed in Redis. Every change made from a browser must come from our own pages with a JSON body (`sameSite`), so another site can't post on someone's behalf.
- **The app on a phone** opens `/api/auth/google?app=<PKCE challenge>` in a browser tab; Google sends it back through the app link `/app/signed-in?code=…`, and the app swaps that two-minute code and its PKCE verifier for its own token (`/api/auth/token`). Works without Google Play services.
- **A TV** signs in from its existing "Sign in with your phone" screen: a phone with Tuned asks `/api/auth/link` for a ten-minute code and seals it into what it sends the TV; a phone without Tuned opens the website, signs in, and approves the TV's code (`/api/account/tv`, or `/account?tv=CODE`), which the TV picks up from the relay (`take` answers with `link`). The website's Pair a TV box does the same with a typed code.
- **A device's token** is random, stored only as a hash on its row in `devices`, and lasts until the device signs out or is removed. Removing a device on the website or another device signs it out at once.

## In the app

- **Signing in** (`data/TunedAccount`, `ui/settings/TunedAccountUi` › `AccountSignIn`): the same on a phone and a TV. The app gets a code from the TV sign-in relay and shows it with a QR code (on a TV) or an Open the website button (on a phone) for `<site>/account?tv=CODE`. The person signs in with Google there and approves; the relay hands the app a one-time code, which it swaps for its token. No Google sign-in or Google keys in the app. The token is sealed with a Keystore key; a 401 means the device was removed, and it forgets the account.
- **Settings › Playlists and account › Tuned account**: Sign in (Premium in the store build, as Drive sync was), then Back up now with when it last did, and Sign out of your Tuned account (frees the seat; the backup and the device's own data stay).
- **The backup** (`data/CloudSyncRepository`, `work/CloudSyncWorker`) runs when the app opens and when it leaves the screen, as Drive sync did: it stores the sync copy with its fingerprint and when it changed, and saves only on top of the version it read, so a race with another device is tried again on the next run.
- **Restore from your Tuned account** on the welcome screen signs in the same way and takes the backup as it is (`ui/onboarding/RestoreFromAccount`).
- **A TV from the website:** the TV's "Sign in with your phone" page (`/tv`) links to the account page for its code; once approved, the TV signs in and restores the backup (`TvSignInScreen`).
- **Signing out of Tuned** (`AppReset`) forgets the account on the device; the backup stays.
- **Send to a TV** from a signed-in phone seals a one-time account code beside the copy (`data/HandoffAccount`), so the TV joins the same account when it takes the copy, without a second sign-in. Backups never carry one.
- **Premium on a signed-in device** (store build, `premium/Premium`): the seat comes from the account (`/api/account/seat`) instead of the per-purchase list, reporting the Play purchases the device sees. Premium bought anywhere counts, the website included; if the account can't confirm it (Play down on the server), Play's own answer on the device still does. Signing in or out asks again at once. Your devices on the Premium screen is the account's list, with the account's limit; Deactivate on this device gives its seat back, and on another device removes it from the account (signing it out). Without an account, the per-purchase list works as before ([premium](premium.md#three-devices-per-purchase)).

## Seats

The rules of [premium](premium.md#three-devices-per-purchase) carry over, now per account: Premium comes with **3 seats**, a device that has a seat keeps it, a device unused for 60 days gives its seat back, and only a clear "full" locks a device (offline or a server error keeps what it last heard).

- **Extra seats**, at most **10 seats** per account (`lib/accounts.mjs`, `seatPlan`, and `core/AccountSeats`, tested). Bought once (`seat_once`, US$4.99) or yearly (`seat_yearly`, US$1.99), in Play or on the website.
- **In Play**, Tuned Premium › More devices shows only on a signed-in account with Premium, and only the matching kind. `seat_once` is a one-time product with multi-quantity on, so the person picks how many in Play's sheet; once the account has recorded it (the seat answer's `recorded` lists token hashes), the app consumes it so more can be bought later, and the account keeps counting it. `seat_yearly` is a subscription, which a Google account holds once, so Play adds at most one yearly seat; more come only from the website, which the Play build never mentions.
- **No mixing**: lifetime Premium takes only one-time seats and yearly Premium only yearly seats. Checkout refuses the other kind, and seats of the other kind never count. A second Premium is refused; lifetime wins if an account somehow has both.
- **Taking a seat** (`/api/account/seat`) holds the account's lock in one transaction, so two TVs asking at once can't both get the last seat. The device reports the Play purchases it sees with each request, as it did before accounts; the server asks Play about each at most every six hours and keeps only the token's hash.
- **The `github` build** never holds a seat and has no limit, as everything is unlocked in it. Its devices still show in Your devices.
- Removing a device on the website has no waiting period, since the person signed in with Google. The order-number page (`/`) stays for Play buyers who never made an account.

## Paying on the website (Stripe)

- `/api/stripe/checkout` makes a Stripe Checkout for one of the four products, each a Stripe price whose id is a Vercel variable (`STRIPE_PRICE_PREMIUM_YEARLY`, `STRIPE_PRICE_PREMIUM_LIFETIME`, `STRIPE_PRICE_SEAT_ONCE`, `STRIPE_PRICE_SEAT_YEARLY`), with `STRIPE_SECRET_KEY`. `STRIPE_TAX=1` turns on Stripe Tax. Prices are set in Stripe, as Play's are in Play Console.
- `/api/stripe/webhook` (signed with `STRIPE_WEBHOOK_SECRET`) turns `checkout.session.completed`, `customer.subscription.created/updated/deleted` and `charge.refunded` into entitlements, keyed by Stripe's ids so a repeat changes nothing. A yearly purchase keeps working for 3 days past its period end while a renewal lands.
- `/api/stripe/portal` opens Stripe's billing page for receipts, cards and cancelling.
- **The Play build never links to the website to pay.** Play's rules allow digital goods inside the app only through Play Billing and forbid pointing to cheaper payment elsewhere. The account shows what it owns wherever it was bought.

## Backups

The app's sync copy (everything sync between devices copies today) as plain JSON, logins included, not encrypted, as Melvin chose for Drive. `/api/backup`: GET the newest (or `?version=N`), PUT a new one on top of the version named in `If-Match` (409 with the newest version when another device saved since, so the device pulls and decides again), DELETE all. The last 5 versions are kept, 4 MB at most, 120 uploads an hour per account. Anyone who got into the Vercel or Neon project could read the logins; Melvin accepted that.

## The website

`/account` (`public/account/`): sign in with Google, Premium and seats, Your devices with Remove, buying Premium or seats, receipts, the backup's date and Delete backup, Pair a TV, Sign out, and Delete account, which removes the account, its devices, purchase records and backups and cancels a Stripe subscription (a Play subscription is cancelled in Play). Play requires a way to delete an account on the web; this is it.

- **Messages sit in their card** (Melvin, 2026-11-19: "when there is an error, it shouldn't show at the top of the page, it should show in the same tile"). A code, Pair a TV, the device sign-in, Devices, buying, the backup and deleting each answer inside their own card, red with a "!" for an error and teal with a tick when it's done, and the field it's about gets a red outline. Only page-wide news (signed out, account deleted, a failed Google sign-in, a purchase coming through) stays at the top.
- **Codes are checked before they're sent** ("there should be validation saying that the format is not correct"): a code must be letters, numbers and dashes, 4 to 24 long, the same rule as the server (`lib/admin/codes.mjs`), and a TV's code must be its eight characters (`normalizeCode` in `public/tv/seal.js`), so a typo says so at once without using up a try.
- **Delete account opens a pop-up** that says what goes and only deletes once "delete" is typed ("the pop up should force the user to type delete"); it replaced pressing the button twice. Cancel, Escape or a tap outside closes it.
- **Google's own button** (Melvin, 2026-11-19: "use the official google login image for logins"). Sign in with Google on the account page and the admin is Google's branded button from its guidelines: the four-colour G (`public/google.svg`) on white with a grey outline, or on near-black in dark mode, Roboto Medium, pill shaped (`.gsi` in `site.css` and `admin/admin.css`).
- **Sign in with Apple, coming soon** (Melvin, 2026-11-19: "add that apple login coming soon"). The sign-in card shows a Sign in with Apple button beside Google, in Apple's black style (white on a dark page), greyed out, with "Sign in with Apple is coming soon." under it. Google is still the only sign-in that works; the button gets wired up when Apple sign-in is built.
- **What Tuned does** (Melvin, 2026-11-19: "on the website, can you help articulate what features tuned has", "after they sign in"): a card on the signed-in account page listing the app's features in plain words, with the Premium ones tagged, kept to what the app does ([premium](premium.md#free-and-premium)). A feature added to or removed from the app updates this list in the same pull request.

## Domain

Melvin, 2026-11-19: "rename the vercel app to tuned", then "website registered: www.tunedplayer.com". The Vercel project is `tuned` (renamed from `tuned-devices`; `tuned.vercel.app` belongs to someone else). The site's address is **`https://www.tunedplayer.com`**; `tunedplayer.com` redirects to it (308), and DNS is at Cloudflare (CNAME to Vercel).

- **`tuned-devices.vercel.app` stays on the project for good, with no redirect.** Tuned 1.13 has it built in for sign-in, backups, TV pairing and the device list, and installed apps can't be changed. A redirect would also drop the `Authorization` header when OkHttp crosses hosts, so the API must keep answering there directly.
- Builds after 1.13 use the new address (`TV_SIGNIN_URL` in `app/build.gradle.kts`). The phone's app link takes both hosts, `www.tunedplayer.com` and the old one, so a newer phone opens a 1.13 TV's QR code too. The bare `tunedplayer.com` isn't in the app link because it redirects, and on Android 11 and older one unverifiable host stops every host from verifying.
- Google sign-in sends people back to the address they came from (`/api/auth/callback` on the same origin), so the Google web client lists both `https://tuned-devices.vercel.app/api/auth/callback` and `https://www.tunedplayer.com/api/auth/callback`. The Stripe webhook, when it is set up, goes to `https://www.tunedplayer.com/api/stripe/webhook`.

## Setting it up (Melvin)

1. Vercel › `tuned` › Storage: add **Neon** (sets `DATABASE_URL`), then run `devices/schema.sql` in its SQL editor. Upstash is still to add too.
2. Google Cloud: a **Web application** OAuth client with the redirect `https://<site>/api/auth/callback`; its id and secret into `GOOGLE_WEB_CLIENT_ID` and `GOOGLE_WEB_CLIENT_SECRET`.
3. Stripe: the four prices, the secret key, and a webhook to `https://<site>/api/stripe/webhook` with the five events above.
4. Play Console: a one-time product `seat_once` (US$4.99, multi-quantity on) and a subscription `seat_yearly` (US$1.99 a year).

Until each exists, that part answers "not set up" and nothing else changes. The site goes live only with a production release, since Vercel builds it from `main`.
