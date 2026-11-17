# Tuned: guide for agents

Tuned is a native Android IPTV player. It signs in to an Xtream Codes-style server (or any M3U and XMLTV link), caches the playlist and guide on the device, keeps them fresh in the background, and plays streams with Media3. The rule for every screen is Melvin's (2026-11-17): **"the UI is clean and intuitive and basically gets out of your way"**, and **speed comes first**. This file holds the rules that apply to every change. The decisions about each area, and why, are in one file per topic under `docs/decisions/` (listed below), so threads working on different areas don't edit the same file. Before working on an area, read its file. If a task seems to need a decision changed, raise it with the owner (Melvin) first.

## Commands

```bash
./gradlew :app:assembleDebug          # debug APK: app/build/outputs/apk/debug/app-debug.apk (id app.tuned.debug)
./gradlew :core:test                  # parser and rule tests, plain JVM, seconds
./gradlew :app:testDebugUnitTest      # app unit tests
./gradlew spotlessApply               # format Kotlin (ktlint); CI runs spotlessCheck
./gradlew detekt :app:lintDebug       # static analysis and Android Lint; any finding fails
npm ci && npm run format              # Prettier for YAML, JSON and Markdown; CI runs format:check
```

## Stack (Melvin, 2026-11-17: "use all modern libraries as much as possible")

Kotlin, Jetpack Compose with Material 3, Navigation Compose (type-safe routes), Room with Paging 3, WorkManager, DataStore, Media3 ExoPlayer (HLS and OkHttp data source), OkHttp, Coil 3, kotlinx.serialization, Play in-app updates. Versions live only in `gradle/libs.versions.toml`; Dependabot raises them weekly. Liftsmith's Android app is a web app in a Capacitor shell; Tuned is fully native because a WebView player can't match Media3 on start time, codecs, captions or picture-in-picture ([architecture](docs/decisions/architecture.md)). What it shares with Liftsmith is the dev tooling: this file and `docs/decisions/`, pull requests into `staging`, a nightly promotion to `main`, one required "CI passed" check, Dependabot, Canadian English, and formatting and lint that fail CI.

## Modules

- `:core`: plain Kotlin, no Android. The M3U and XMLTV parsers, sorting streams into Live TV, Movies and Shows, country detection, episode numbering, Xtream URLs. Anything that can be tested without a device goes here, with a test.
- `:app`: the Android app. `data/` (Room, DataStore settings, encrypted login, sync), `work/` (background refresh), `player/` (PlayerActivity), `ui/` (Compose screens, one screen and its ViewModel per file).

## Product rules

- **Get out of the way.** Opening the app shows channels from the cache at once; nothing waits on the network. The player shows only video until a tap; the controls hide themselves. No pop-ups, no onboarding beyond sign-in.
- **Speed first** ([speed](docs/decisions/speed.md)): lists are paged straight from Room; downloads stream into the database in batches; nothing heavy runs before the first frame.
- **Customizable** (Melvin, 2026-11-17: "customizing most things in the settings"): a new behaviour gets a setting when people could reasonably want it either way, with the common choice as the default.
- **Never ask for, log or commit a login.** The app stores it encrypted until sign-out ([security](docs/decisions/security.md)). Error messages go through `XtreamUrls.redact`. Playlists and guides are never committed (`.gitignore` blocks `*.m3u`, `*.xmltv`, `.env`).
- **English is Canadian English** (as in Liftsmith): colour, favourite, behaviour, centre, cancelled, but -ize (organize). Code identifiers stay as they are.

## Code conventions

- Formatting is ktlint's (`.editorconfig`, width 140) through Spotless, and Prettier's for YAML, JSON and Markdown. Don't hand-format.
- detekt (`config/detekt/detekt.yml`) and Android Lint (`warningsAsErrors`) fail CI on any finding. Fix the cause; suppress only with a comment saying why.
- Every string a person reads is in `res/values/strings.xml`; numbers that change a word use `<plurals>`.
- A bug fix comes with a test that fails without it. Flaky tests are fixed at the cause, never retried.

## CI/CD (like Liftsmith)

- `ci.yml` on every pull request and push to `main` and `staging`: formatting (Prettier, ktlint), lint and static analysis (detekt, Android Lint), unit tests, and the Android build (debug APK uploaded as an artifact to install and try, plus an unsigned release build so R8 problems show up here). **"CI passed"** is the one required check. A tree that already passed, or a Markdown-only change after a passing commit, skips the work.
- `promote-staging.yml`: `main` is production and every pull request targets `staging`. At 09:00 UTC each night it promotes staging to main when "CI passed" is green, then merges main back into staging. Run it by hand to ship sooner.
- `release.yml`: on every promotion and on demand, a signed App Bundle to the Play **internal** track. Installed copies update themselves through Play in-app updates ([updates](docs/decisions/updates.md)). Version name from `package.json`, version code from the run number. Skips with a notice until its secrets exist.
- Secrets: `ANDROID_KEYSTORE_B64` (base64 upload keystore, alias `upload`), `ANDROID_KEYSTORE_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`, optional `PROMOTE_TOKEN`. The first upload must be done by hand in Play Console before the API can upload.
- Dependabot targets `staging` weekly: Gradle (minor and patch grouped), npm, GitHub Actions.

## Decision record

One file per topic in `docs/decisions/`, A to Z. Add a line when you add a topic.

- [Architecture](docs/decisions/architecture.md): native Kotlin over Capacitor, modules, data flow.
- [Auto-tune](docs/decisions/auto-tune.md): finding a working English stream for a live event, one connection at a time.
- [Branding](docs/decisions/branding.md): name, icon, colours, themes.
- [Playlist and guide](docs/decisions/playlist.md): Xtream URLs, how streams are sorted, countries, refresh.
- [Player](docs/decisions/player.md): Media3 setup, controls, captions, audio, picture-in-picture.
- [Security](docs/decisions/security.md): login storage, cleartext HTTP, backups.
- [Speed](docs/decisions/speed.md): what keeps the app fast, and the budget.
- [Updates](docs/decisions/updates.md): Play in-app updates.
