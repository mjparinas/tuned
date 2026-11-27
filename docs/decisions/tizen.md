# Samsung TVs (Tizen)

**What** (Melvin, 2026-11-27: "build both vega and tizen", un-parking the 2026-11-19 plan). Tuned for Samsung TVs, built as a Tizen web app that plays through Samsung's own hardware player (AVPlay), sharing its playlist rules with Android and with the Vega OS (new Fire TV) app. Melvin publishes it to Samsung's store himself, from his own TV Seller Office account.

**Choices taken from the plan** (2026-11-27; the plan's open questions were never answered, so its recommendations stand until Melvin changes them):

- **Samsung TVs from 2020 on** (Tizen 5.5, Chromium 69). Everything is built for that browser: plain ES2015 out of the Kotlin compiler, no newer JavaScript, `Intl.DisplayNames` not assumed.
- **Premium only through the Tuned account** (as on Fire TV, Melvin 2026-11-26): the TV signs in to the account and Premium follows it. No Samsung Checkout, no prices or purchase links on the TV.
- **Sign in from your phone in v1**, through the TV sign-in relay Android TV already uses (`devices/lib/tv.mjs`): the TV shows a code and a QR code for tunedplayer.com/tv, where a phone sends a playlist or signs the TV in to the account. Typing on the TV stays as the fallback.
- **Same repository**, under `tv/`.

## How it's built

- **`tv/core` (Gradle `:tvcore`)**: :core's plain-Kotlin files compiled a second time, to JavaScript (Kotlin/JS, one ES module, `tuned-core`). The list of files is in `tv/core/build.gradle.kts`; everything else in :core uses Java and stays Android only. :core itself didn't become multiplatform, so Android builds exactly as before: the few Java calls the shared files made (URL encoding, accents, country names, A to Z order) moved into `Platform` (`core/.../JvmPlatform.kt` on Android, `tv/core/src/jsMain/.../Platform.kt` here, same names), the M3U reader became a line-at-a-time `M3uLines` that `M3uParser` wraps, and the three calls that need `java.time` or `java.util.Locale` (`XtreamUrls.timeshift`, `Regions.displayName(region, locale)`) moved to their own Android-only files. A rule fixed in :core is fixed on every TV the next time it builds. `TunedCore.kt` is what TypeScript sees: strings, numbers and arrays only. Its JavaScript tests run in CI with the rest.
- **`tv/shared` (`@tuned/shared`)**: plain TypeScript both TV apps use below their screens: reading an Xtream server (its API, falling back to its M3U download) or an M3U link as it downloads, an XMLTV guide read as it downloads, the Tuned account and the phone sign-in (the TV's half of `TvHandoff`, opening exactly what `devices/public/tv/seal.js` and the Android app seal), and the one-connection guard. It touches nothing platform-specific (no DOM, IndexedDB, Worker or `TextDecoderStream`): the app hands in `fetch`, a string store, WebCrypto and a gunzip, because Vega's Hermes has a different set of each. Parsers take text in pieces (`feed`, `end`), since Vega's fetch can't stream.
- **`tv/tizen`**: the Samsung app itself (screens, AVPlay, the `.wgt` package). Coming in the next pull request.
- **CI**: the **Samsung TV app** job builds `tv/core`, runs its tests and `tv/shared`'s, whenever `tv/`, :core or the Gradle setup changes (`scripts/ci-changes.mjs`: `tizen=` and `vega=`). The Vega app has its own job.

## Rules carried over

- **One stream connection, ever** ([scope](scope.md)): every player sits behind `OneConnection` (`tv/shared/src/player.ts`), which closes the old stream, and waits for the close, before the next opens. Its test changes channel three times at once and checks there were never two.
- **Waiting is never a mystery**: reading a playlist names each numbered step ("2 of 4: Live TV") with bytes so far and of how many, and `core.remaining` gives the same "about 3 min left" as Android's `Eta`.
- **Never log a login**: everything that reaches an error message goes through Xtream's `redact` from :core, and an M3U link is never shown whole.
- **No sleep timer and no second video**, as everywhere ([scope](scope.md)).

## Left out on a TV web app

Downloads, recording, pausing live TV, casting, DLNA, "open in another player", picture-in-picture, widgets and the GitHub self-updater: a Tizen web app has no background, little storage and no sockets. The playlist and guide refresh when the app opens and every few hours while it's open, instead of in the background. Samsung's store updates the app.
