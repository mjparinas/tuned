# Samsung TVs (Tizen)

**What** (Melvin, 2026-11-27: "build both vega and tizen", un-parking the 2026-11-19 plan). Tuned for Samsung TVs, built as a Tizen web app that plays through Samsung's own hardware player (AVPlay), sharing its playlist rules with Android and with the Vega OS (new Fire TV) app. Melvin publishes it to Samsung's store himself, from his own TV Seller Office account.

**Choices taken from the plan** (2026-11-27; the plan's open questions were never answered, so its recommendations stand until Melvin changes them):

- **Samsung TVs from 2020 on** (Tizen 5.5, Chromium 69). Everything is built for that browser: plain ES2015 out of the Kotlin compiler, no newer JavaScript, `Intl.DisplayNames` not assumed.
- **Premium only through the Tuned account** (as on Fire TV, Melvin 2026-11-26): the TV signs in to the account and takes one of its Premium seats (`POST /api/account/seat`, as the Fire TV builds do), so it counts toward the device limit. A seat "active" unlocks Premium, "full" or no Premium locks it, and offline the last answer stands. No Samsung Checkout, no prices or purchase links on the TV.
- **Sign in from your phone in v1**, through the TV sign-in relay Android TV already uses (`devices/lib/tv.mjs`): the TV shows a code and a QR code for tunedplayer.com/tv, where a phone sends a playlist or signs the TV in to the account. Typing on the TV stays as the fallback.
- **Same repository**, under `tv/`.

## How it's built

- **`tv/core` (Gradle `:tvcore`)**: :core's plain-Kotlin files compiled a second time, to JavaScript (Kotlin/JS, one ES module, `tuned-core`). The list of files is in `tv/core/build.gradle.kts`; everything else in :core uses Java and stays Android only. :core itself didn't become multiplatform, so Android builds exactly as before: the few Java calls the shared files made (URL encoding, accents, country names, A to Z order) moved into `Platform` (`core/.../JvmPlatform.kt` on Android, `tv/core/src/jsMain/.../Platform.kt` here, same names), the M3U reader became a line-at-a-time `M3uLines` that `M3uParser` wraps, and the three calls that need `java.time` or `java.util.Locale` (`XtreamUrls.timeshift`, `Regions.displayName(region, locale)`) moved to their own Android-only files. A rule fixed in :core is fixed on every TV the next time it builds. `TunedCore.kt` is what TypeScript sees: strings, numbers and arrays only. Its JavaScript tests run in CI with the rest.
- **`tv/shared` (`@tuned/shared`)**: plain TypeScript both TV apps use below their screens: reading an Xtream server (its API, falling back to its M3U download) or an M3U link as it downloads, an XMLTV guide read as it downloads, the Tuned account and the phone sign-in (the TV's half of `TvHandoff`, opening exactly what `devices/public/tv/seal.js` and the Android app seal), and the one-connection guard. It touches nothing platform-specific (no DOM, IndexedDB, Worker or `TextDecoderStream`): the app hands in `fetch`, a string store, WebCrypto and a gunzip, because Vega's Hermes has a different set of each. Parsers take text in pieces (`feed`, `end`), since Vega's fetch can't stream.
- **`tv/tizen`**: the Samsung app itself, Preact screens bundled by Vite for Chromium 69 (`scripts/check-build.mjs` fails the build on newer JavaScript), playing through AVPlay (`src/player/avplay.ts`) behind `OneConnection`; in a desktop browser a `<video>` stands in, which is how CI drives it. The lists and guide live in IndexedDB (`src/db.ts`), the logins sealed with a WebCrypto key the TV can't export (`src/secret.ts`). `npm run dev` runs it in a browser, `npm run build && npm run package` makes the unsigned `dist-wgt/Tuned.wgt`, and `npm run e2e` drives every screen with the remote's keys against a stand-in provider.
- **CI**: the **Samsung TV app** job builds `tv/core`, runs its tests and `tv/shared`'s, then the app's tests, type check, build and `.wgt`, and the end-to-end run, which saves a picture of each screen (`tizen-screens`) and the package (`tuned-tizen-wgt`) as artifacts, whenever `tv/`, :core or the Gradle setup changes (`scripts/ci-changes.mjs`: `tizen=` and `vega=`). The Vega app has its own job.

## The app (built 2026-11-27)

- **First screen**: until there's a playlist, the welcome shows a code and its QR code for tunedplayer.com/tv. The phone sends a playlist (Free adds one; with Premium every playlist it sends) or signs the TV in to the Tuned account. "Type a login with the remote" is the other way in: an Xtream login or a playlist link, typed with the TV's keyboard.
- **First download**: each step named and numbered ("Step 2 of 4, Downloading live channels"), bytes of the full size or bytes so far, and "about 3 min left" once it can tell. A failure says why in words (the login, the internet, the server, an empty playlist), with Try again and Change login.
- **Home**: Tuned's mark atop a rail (Live TV, Movies, Shows, Search, Settings); landing on a rail item opens its section, no OK needed. Up and down stay inside the rail or the section; only left and right cross. Lists draw a screenful at a time, so 20,000 channels move like 20. The section is "Movies", as in the Android app, never "Films" (Melvin, 2026-11-27: "instead of films can you call them movies").
- **Live TV**: groups across the top (picked after a short pause on one, and kept per section until changed), channels with their numbers and logos, and beside them now and next from the guide with how far in it is. Yellow adds the channel to Favourites.
- **Movies and Shows**: groups and a poster grid; a movie's page has Play, or Resume where it was left and Play from the start; a show's page has its seasons and episodes (from the playlist, or the Xtream server's episode list).
- **Player**: only the picture until a key. OK or Info shows the channel, now and next, and the time; it hides after 5 seconds. Up and down or channel up and down change channel within the group, number keys type a channel number, the flip-back key returns to the last one. Movies skip 10 seconds with left and right. Audio and captions are buttons in the top row when the stream has a choice. A live stream that ends reconnects; one that won't open says so with Try again. Back hides the controls, then stops and closes the stream.
- **Settings**: the Tuned account (signed in, Free or Premium, Sign in with your phone), Refresh now with its numbered step and how it ended, the playlists (Use, Remove, Add another, which is Premium), Channel numbers, Hide adult groups, the App log and Sign out of everything, which clears the playlists, lists, guide, account and the key.
- **Words**: every string is in `src/strings.ts`, English first; the other ten languages ([languages](languages.md)) follow in their own pull request, as on Vega.

## Publishing (Melvin)

The `.wgt` CI builds is unsigned. Samsung's store and a TV in developer mode take only a package signed with Samsung's certificates, which need Melvin's own Samsung account:

1. Make a Samsung TV Seller Office account (seller.samsungapps.com/tv) and register Tuned as a new app.
2. In Tizen Studio's Certificate Manager, make a Samsung author certificate and a distributor certificate (Public privilege, with the TV's DUID added to test on his own TV).
3. Sign: `tizen package -t wgt -s <profile> -- Tuned.wgt`, then install it on a TV in developer mode to try it, and upload it in Seller Office with the listing (screenshots from the `tizen-screens` artifact; the listing says Tuned includes no content).

## Rules carried over

- **One stream connection, ever** ([scope](scope.md)): every player sits behind `OneConnection` (`tv/shared/src/player.ts`), which closes the old stream, and waits for the close, before the next opens. Its test changes channel three times at once and checks there were never two.
- **Waiting is never a mystery**: reading a playlist names each numbered step ("2 of 4: Live TV") with bytes so far and of how many, and `core.remaining` gives the same "about 3 min left" as Android's `Eta`.
- **Never log a login**: everything that reaches an error message goes through Xtream's `redact` from :core, and an M3U link is never shown whole.
- **No sleep timer and no second video**, as everywhere ([scope](scope.md)).

## Left out on a TV web app

Downloads, recording, pausing live TV, casting, DLNA, "open in another player", picture-in-picture, widgets and the GitHub self-updater: a Tizen web app has no background, little storage and no sockets. The playlist and guide refresh when the app opens and every few hours while it's open, instead of in the background. Samsung's store updates the app.
