# Architecture

**Native Kotlin, not Capacitor (2026-11-17).** Melvin asked for "the same tech stack as Liftsmith for Android" and for an "android native app". Liftsmith's Android app is a Next.js web app in a Capacitor shell. For a video player that shell is the wrong tool: HTML5 video in a WebView can't play MPEG-TS (the format IPTV servers send), has no control over buffering, track selection or caption styling, and picture-in-picture is awkward. So the app is native (Kotlin, Compose, Media3) and the _tooling_ follows Liftsmith: docs, branches, CI shape, release flow, Dependabot, formatting and lint.

**Modules.** `:core` is plain Kotlin with no Android dependency: parsers and rules, tested on the JVM in seconds. `:app` is the Android app.

**Data flow.** Sign-in saves the login (encrypted) and downloads the playlist and guide once in the foreground. `SyncRepository` streams each download straight into Room inside one transaction; the screens read Room through Paging, so they never wait on the network. WorkManager refreshes both in the background on their own schedules.

**No dependency injection framework.** One `AppContainer` built in `TunedApp` holds the database, OkHttp client, settings, login store and scheduler, created lazily. The app is small enough that Hilt would add build time and start-up cost for nothing.

**Player on Views.** `PlayerActivity` uses Media3's `PlayerView` (Views), not Compose, because it is Media3's own tested surface with the standard controls (captions button, audio and speed menu), and it starts fastest. Everything else is Compose.
