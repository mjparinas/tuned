# Premium and store builds

Tuned is free with no ads, and a Premium unlock covers the power features. Melvin agreed the plan on 2026-11-17 ([plan](https://claude.ai/artifact/2WZsayqCX4acMnvmVmnBHX)): free plus Premium with no ads, the split below, the prices, an empty server field in store builds, and GitHub releases staying free with everything unlocked. Fire TV (Amazon Appstore) comes after Play; the store listing's support email and website are still open.

## Two builds

- **`github`** is what GitHub Releases ship (`release.yml` builds `assembleGithubRelease`). It updates itself from GitHub ([updates](updates.md)), fills in the default server at sign-in, and has every Premium feature unlocked, so Melvin's copies and everyone already on the GitHub releases lose nothing.
- **`play`** is for Google Play and later other stores (`release.yml` builds `bundlePlayRelease` for the internal track). It drops `REQUEST_INSTALL_PACKAGES` and the GitHub updater, because stores update the app themselves and Play doesn't allow the permission, and the server field starts empty.
- Both share the application id `app.tuned` and the upload key, so one can replace the other.
- `BuildConfig.GITHUB_UPDATES` and `BuildConfig.DEFAULT_SERVER` are the only differences in code; anything new that differs gets its own `BuildConfig` field in `app/build.gradle.kts`.

## Store policy

Play's rules cover "any content your app displays or links to", and pay-TV companies file takedowns against players that ship no content. So a store build looks like a pure player:

- No provider is filled in or named anywhere. That is why the server field is empty in `play`.
- The listing, screenshots and feature graphic name no channel, network, provider, league, team, fight or film. Screenshots come from a made-up demo playlist, never a real one.
- The listing and sign-in say Tuned includes no content and needs the person's own playlist.

## Free and Premium (being built)

Free: one playlist with its guide; Live TV, Movies, Shows, search, favourites, Continue Watching; the whole player (captions, audio, picture-in-picture, casting); the grid guide, channel numbers and flip back; TMDB details; backup copies; auto-tune and the Live Events page; open in another player; Android TV.

Premium: more than one playlist; more than one profile (with kids profiles and PINs); pause and rewind live TV; recording; more than 3 downloads at a time; following events and their alerts; sync between devices; Trakt; themes and accent colours other than the default.

A locked item shows a lock and opens one Premium screen with the price and the trial. No pop-ups, banners, nags or countdowns.

## Price (being built)

Sold through Google Play Billing, checked on the device and cached, so Premium works offline and opening the app never waits on Play.

- US$9.99 a year (an auto-renewing subscription, `premium_yearly`) with a 7-day free trial.
- US$24.99 once for life (`premium_lifetime`), US$14.99 for the first 30 days after launch.
- No ads, ever: they slow the first screen and add trackers.
