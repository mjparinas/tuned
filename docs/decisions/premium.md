# Premium and store builds

Tuned is free with no ads, and a Premium unlock covers the power features. Melvin agreed the plan on 2026-11-17 ([plan](https://claude.ai/artifact/2WZsayqCX4acMnvmVmnBHX)): free plus Premium with no ads, the split below, the prices, an empty server field in store builds, and GitHub releases staying free with everything unlocked. On 2026-11-18 he decided no Fire TV (Amazon Appstore) for now ("not going to do fire tv for now"), so Play is the only store; the listing's support email is his own Gmail address. The privacy policy still needs a page to live on.

## Two builds

- **`github`** is what GitHub Releases ship (`release.yml` builds `assembleGithubRelease`). It updates itself from GitHub ([updates](updates.md)), and has every Premium feature unlocked, so Melvin's copies and everyone already on the GitHub releases lose nothing.
- **`play`** is for Google Play and later other stores (`release.yml` builds `bundlePlayRelease` for the internal track). It drops `REQUEST_INSTALL_PACKAGES` and the GitHub updater, because stores update the app themselves and Play doesn't allow the permission. Neither build fills in a server (Melvin, 2026-11-19), so the two forms are the same.
- Both share the application id `app.tuned` and the upload key, so one can replace the other.
- **Release › Run workflow › Signed store bundle only** builds the signed `play` bundle as the `tuned-release-aab` artifact without a GitHub release, so a build can go to a Play testing track without a production release. Play only lets you create in-app products once it has seen a build with Play Billing, and the first upload is by hand (2026-11-18).
- `BuildConfig.GITHUB_UPDATES`, `PREMIUM_ALWAYS` and `DEVICES_URL` are the only differences in code; anything new that differs gets its own `BuildConfig` field in `app/build.gradle.kts`.

## Store policy

Play's rules cover "any content your app displays or links to", and pay-TV companies file takedowns against players that ship no content. So a store build looks like a pure player:

- No provider is filled in or named anywhere. That is why the server field is empty in `play`.
- The listing, screenshots and feature graphic name no channel, network, provider, league, team, fight or film. Screenshots come from a made-up demo playlist, never a real one.
- The listing and sign-in say Tuned includes no content and needs the person's own playlist.

## Free and Premium

Free: one playlist with its guide; Live TV, Movies, Shows, search, favourites, Continue Watching; the whole player (captions, audio, picture-in-picture, casting); the grid guide, channel numbers and flip back; TMDB details; backup copies; auto-tune and the Live Events page; Android TV.

Premium: more than one playlist; your own lists beside Favourites, and renaming channels and changing their logos ([lists](lists.md)); more than one profile (with kids profiles and PINs); the Premium profile pictures ([profiles](profiles.md)); pause and rewind live TV; recording; more than 3 downloads at a time; following events and their alerts; the backup in a Tuned account that keeps devices the same ([accounts](accounts.md)); Trakt; themes and accent colours other than the default.

How the locks work (`premium/`):

- `Premium` holds one `unlocked` state. The `github` build is always unlocked (`BuildConfig.PREMIUM_ALWAYS`). The `play` build remembers the last answer on the device and asks Play again in the background each time the app comes to the front, so Premium works offline and nothing waits on Play.
- A locked setting (live rewind, Tuned account sign-in, Trakt sign-in) shows "Premium" and a lock (`LockedRow`). A locked action (adding a second playlist or profile, a fourth download, following an event, turning an alert on, recording, a non-default accent, a Premium profile picture, Add to list, Rename channel, Change logo) opens the Premium screen instead (`Premium.allow`). Turning things off, browsing and Tune always work.
- Pause and rewind live TV is off in the player while locked, whatever the setting says.
- `PremiumActivity` is the one Premium screen: what's included, the two prices as Play writes them, the trial, Restore purchase. It opens only from a locked feature or Settings › About › Tuned Premium (store build only). No pop-ups, banners, nags or countdowns.
- Billing lives only in the `play` build (`app/src/play/kotlin/app/tuned/premium/StoreFor.kt`, Play Billing 7); the `github` build has no billing library at all. Purchases are acknowledged when Play reports them, since Play refunds unacknowledged ones after three days.

## Three devices per purchase

Accounts carry these rules over per account, with extra seats bought once or yearly up to 10 ([accounts](accounts.md)); what follows is how a purchase without an account works.

Melvin, 2026-11-19: "for the paid version, it should limit the number of devices they can activate. like a phone, a tv, and maybe one more. beyond that, it should stop them", and a blocked device stays blocked "until they uninstall a version and deactivate it". Play itself puts a purchase on every device signed in to the Google account, so the count lives in a small device list on Vercel (`devices/`), which Melvin chose over Cloudflare, a file in the person's Drive, or Firebase (he already pays for Vercel Pro, whose terms allow a paid app; Hobby doesn't).

- **The rule** (`core/DeviceSeats`, `devices/lib/seats.mjs`, both tested). Three devices per purchase. A device that already has a seat keeps it; a device with no seat takes a free one or hears "full" and stays locked, with Your devices on the Premium screen saying which devices hold the seats. A device that hasn't been used for 60 days gives its seat back on its own, so a lost or broken phone never holds one for good.
- **Never wrongly locked out** (Melvin's priority: reliability). Only a clear "full" answer locks a device. Offline, the list down, Play unreachable or a server error keep what the device last heard, and a device that has never heard anything is let in and asked again later. That leaves a way round the limit for someone who blocks the address; a paying person losing Premium to an outage would be worse.
- **Reinstalling reuses the seat.** The device id is Android's per-app id (`ANDROID_ID`), which survives an uninstall, hashed on the device before it is sent.
- **Deactivate** is on Settings › About › Tuned Premium › Your devices, for every device, from any device that holds a seat (so a lost phone can be removed from the TV). A locked-out device can't free someone else's seat, or the limit would mean nothing. A device that frees its own seat stays off until Use Premium on this device.
- **The website, for someone who lost every device** (Melvin, 2026-11-19: "The user should login on the website and be able deregister a device if they lost all their devices"). Play never says which Google account bought something, so a Google sign-in can't find a purchase; Melvin picked the order number from Google Play's receipt email (GPA.…) over linking a Google account in the app. The device list's own address shows the page (`devices/public/index.html`): the order number lists the devices, and removes one or all of them once every 30 days per purchase, with 10 lookups an hour per visitor. A locked-out device's Your devices list points to it.
- **What's stored** (Upstash Redis on the Vercel project): a SHA-256 of the purchase token, the hashed device id, the device's name ("Pixel 8"), when it was last used, and a hash of the order number for the website. No email, no Google account, no IP address, nothing watched.
- **Purchases are checked with Play** (`devices/lib/play.mjs`, the Play Developer API as a service account with "View financial data"), at most every six hours per purchase, so a made-up token can't take seats.
- **Builds.** The address comes from the repository variable `PREMIUM_DEVICES_URL` into `BuildConfig.DEVICES_URL`; a store build without it has no limit, and the `github` build never has one. The Vercel project is `tuned-devices` (root `devices/`, production from `main`, built only when `devices/` changes). It needs the Vercel environment variables `GOOGLE_PLAY_SERVICE_ACCOUNT` and the Upstash `KV_REST_API_URL` and `KV_REST_API_TOKEN`.

## Price

Sold through Google Play Billing, checked on the device and cached, so Premium works offline and opening the app never waits on Play.

- US$9.99 a year (an auto-renewing subscription, `premium_yearly`) with a 7-day free trial.
- US$24.99 once for life (`premium_lifetime`), US$14.99 for the first 30 days after launch.
- Prices, the trial and the launch discount are set in Play Console on those two product ids; the app shows whatever Play returns.
- No ads, ever: they slow the first screen and add trackers.
