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

Kotlin, Jetpack Compose with Material 3, Navigation Compose (type-safe routes), Room with Paging 3, WorkManager, DataStore, Media3 ExoPlayer (HLS and OkHttp data source), OkHttp, Coil 3, kotlinx.serialization, Play in-app updates and a GitHub Releases updater. Versions live only in `gradle/libs.versions.toml`; Dependabot raises them weekly. Liftsmith's Android app is a web app in a Capacitor shell; Tuned is fully native because a WebView player can't match Media3 on start time, codecs, captions or picture-in-picture ([architecture](docs/decisions/architecture.md)). What it shares with Liftsmith is the dev tooling: this file and `docs/decisions/`, pull requests into `staging`, promotion to `main`, one required "CI passed" check, Dependabot, Canadian English, and formatting and lint that fail CI.

## Modules

- `:core`: plain Kotlin, no Android. The M3U and XMLTV parsers, sorting streams into Live TV, Movies and Shows, country detection, episode numbering, Xtream URLs. Anything that can be tested without a device goes here, with a test.
- `:app`: the Android app. `data/` (Room, DataStore settings, encrypted login, sync), `work/` (background refresh), `player/` (PlayerActivity), `ui/` (Compose screens, one screen and its ViewModel per file).

## Product rules

- **Get out of the way.** Opening the app shows channels from the cache at once; nothing waits on the network. The player shows only video until a tap; the controls hide themselves. No pop-ups, no onboarding beyond adding a first playlist ([onboarding](docs/decisions/onboarding.md)).
- **Speed first** ([speed](docs/decisions/speed.md)): lists are paged straight from Room; downloads stream into the database in batches; nothing heavy runs before the first frame.
- **Customizable** (Melvin, 2026-11-17: "customizing most things in the settings"): a new behaviour gets a setting when people could reasonably want it either way, with the common choice as the default.
- **Never ask for, log or commit a login.** The app stores it encrypted until sign-out ([security](docs/decisions/security.md)). Error messages go through `XtreamUrls.redact`; log lines through `AppLog`, which scrubs them ([diagnostics](docs/decisions/diagnostics.md)). Playlists and guides are never committed (`.gitignore` blocks `*.m3u`, `*.xmltv`, `.env`).
- **One stream connection, ever** (Melvin, 2026-11-17: "i dont want to get banned"). The player, auto-tune, backup copies, channel scans and downloads share one connection: the old stream closes before the next opens, nothing prefetches, and there is no multi-view or live preview ([scope](docs/decisions/scope.md)).
- **Saved before streamed.** Anything downloaded plays from the device, never the stream ([offline](docs/decisions/offline.md)). TMDB art and details come from the device's cache first and the network only for what's missing ([player](docs/decisions/player.md)).
- **Real names over numbers.** Show an episode's TMDB name where there is one, rather than "S1 E1" (Melvin, 2026-11-17).
- **No second video on screen, and no sleep timer** (Melvin, 2026-11-17). Android's picture-in-picture, the one stream shrinking to a small window when you leave the app, stays. A second channel inset over the first is banned because it is a second stream. Don't build or propose a sleep timer ([scope](docs/decisions/scope.md)).
- **English is Canadian English** (as in Liftsmith): colour, favourite, behaviour, centre, cancelled, but -ize (organize). Code identifiers stay as they are.

## Code conventions

- Formatting is ktlint's (`.editorconfig`, width 140) through Spotless, and Prettier's for YAML, JSON and Markdown. Don't hand-format.
- detekt (`config/detekt/detekt.yml`) and Android Lint (`warningsAsErrors`) fail CI on any finding. Fix the cause; suppress only with a comment saying why.
- Every string a person reads is in `res/values/strings.xml`; numbers that change a word use `<plurals>`.
- Fire-and-forget coroutines use `launchSafely(tag)`, and caught failures go to `AppLog` (`attempt` instead of `runCatching`), so nothing fails silently and nothing crashes the app ([diagnostics](docs/decisions/diagnostics.md)).
- A bug fix comes with a test that fails without it. Flaky tests are fixed at the cause, never retried.

## CI/CD (like Liftsmith)

- `ci.yml` on every pull request and push to `main` and `staging`: formatting (Prettier, ktlint), lint and static analysis (detekt, Android Lint), unit tests, and the Android build (debug APK uploaded as an artifact to install and try, plus an unsigned release build so R8 problems show up here). **"CI passed"** is the one required check. A tree that already passed, or a Markdown-only change after a passing commit, skips the work.
- `promote-staging.yml`: `main` is production and every pull request targets `staging`. Run by hand, it promotes staging to main when "CI passed" is green (a direct merge, so it needs no repository setting), then merges main back into staging. **Every production release needs Melvin's go-ahead, each time** (2026-11-17; he holds releases until a batch of features is in), so the nightly schedule Liftsmith uses is off.
- `release.yml`: on every promotion and on demand, a signed APK published as a **GitHub release** (`v<version>`, no build number), which installed copies offer to update to; plus a signed App Bundle for the Play **internal** track once its secret exists ([updates](docs/decisions/updates.md)). The version is semver, bumped automatically from the commits since the last release (`scripts/next-version.mjs`; `package.json` is a floor), and the version code comes from it (1.2.3 is 1002003). Skips with a notice until its secrets exist.
- Secrets: `ANDROID_KEYSTORE_B64` (base64 upload keystore, alias `upload`), `ANDROID_KEYSTORE_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`, optional `PROMOTE_TOKEN`. The first upload must be done by hand in Play Console before the API can upload.
- **Pull request titles follow Conventional Commits**, because the squashed title decides the next version (Melvin, 2026-11-17: "Auto bump semver versions with each update"): `feat: …` bumps the minor, `fix: …` and anything else the patch, `feat!: …` or a `BREAKING CHANGE:` line the major.
- Dependabot targets `staging` weekly: Gradle (minor and patch grouped), npm, GitHub Actions.

## How work lands (Melvin, 2026-11-17)

- One pull request per feature or fix, into `staging`. Merge it yourself once "CI passed" is green, and fix failing checks and merge conflicts without asking ("auto merge ci when done, auto fix conflicts and ci"). Ask Melvin only for what needs him: secrets, keys, money, or a choice that changes the product.
- Production (staging to `main`, which publishes a GitHub release) only with his go-ahead, each time.
- Raw speed, resilience and reliability come first in every trade-off. Measure speed work before and after, and report it in plain numbers.

## Decision record

Melvin, 2026-11-17: his product and development decisions "should be updated and kept in sync every time new features are added or removed". So:

- **Every pull request that adds, changes or removes a feature updates the record in the same pull request**: the topic's file in `docs/decisions/` (what was decided, Melvin's words and the date, and why), and this file when a rule that applies everywhere changes. A removed feature's text is deleted or marked as removed, never left describing code that is gone.
- A decision made in chat before it is built goes in [scope](docs/decisions/scope.md) under "Decided, being built", and moves to its topic when it merges. Things Melvin ruled out go there too, so nobody suggests them again.
- CI enforces it: the **Decision record** job (`scripts/decision-record.mjs`, with tests) fails a pull request whose title is `feat…:`, breaking (`!`), a revert, or says remove, drop or delete, unless it changes `AGENTS.md` or a file in `docs/decisions/`.

One file per topic in `docs/decisions/`, A to Z. Add a line when you add a topic.

- [Architecture](docs/decisions/architecture.md): native Kotlin over Capacitor, modules, data flow.
- [Auto-tune](docs/decisions/auto-tune.md): finding a working English stream for a live event, one connection at a time.
- [Branding](docs/decisions/branding.md): name, icon, colours, themes.
- [Channel scan](docs/decisions/channel-scan.md): finding dead channels without blaming them for the internet or the server.
- [Diagnostics](docs/decisions/diagnostics.md): crash reports, `launchSafely` and `attempt`, the developer log, keeping logins out of it.
- [Large screens](docs/decisions/large-screens.md): navigation rail, list and detail panes, the guide beside the player on tablets.
- [Live events](docs/decisions/events.md): today's games and fights from the guide, Tune, following and alerts.
- [Offline downloads](docs/decisions/offline.md): saving films and episodes, one connection at a time.
- [Onboarding and playlists](docs/decisions/onboarding.md): the welcome screen, one form per playlist kind (Xtream, link, file), Settings › Playlists.
- [People](docs/decisions/people.md): actor filmography pages, oldest first, with what is in the playlist marked.
- [Playlist and guide](docs/decisions/playlist.md): Xtream URLs, how streams are sorted, countries and adult categories across providers, refresh.
- [Player](docs/decisions/player.md): Media3 setup, controls, captions, audio, cast, backup copies, TMDB details.
- [Profiles](docs/decisions/profiles.md): who's watching, kids profiles, profile PINs.
- [Scope](docs/decisions/scope.md): what Tuned will never do, and what's decided but not built yet.
- [Security](docs/decisions/security.md): login storage, cleartext HTTP, backups.
- [Speed](docs/decisions/speed.md): what keeps the app fast, and the budget.
- [TV](docs/decisions/tv.md): Android TV launcher, focus outlines, remote keys in the player.
- [Updates](docs/decisions/updates.md): GitHub Releases updater now, Play in-app updates later, semver tags.
