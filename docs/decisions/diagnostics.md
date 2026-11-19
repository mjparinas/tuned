# Diagnostics: crashes, errors and the log

Melvin, 2026-11-17: "optimize for the app not crashing or being unreliable", and for anything unhandled, "a developers mode or debug mode in the settings where I can see the logs, and then I can use that to fix the app."

## What happens to a failure

- **Background work never crashes the app.** `AppContainer.appScope` carries a `CoroutineExceptionHandler` that logs. ViewModels and activities launch fire-and-forget work with `launchSafely(tag) { … }` (`diagnostics/Safely.kt`), which logs a failure and leaves the screen working. Work whose result the person waits for still handles its errors itself and says what went wrong (sign-in, sync, refresh).
- **`attempt(tag, what) { … }`** replaces `runCatching` where the failure is worth knowing about. Unlike `runCatching` it never swallows `CancellationException`, so a cancelled coroutine stops instead of carrying on.
- **Background jobs** (refresh, downloads, channel scans) run inside `logFailures`, so an unexpected failure is logged and ends that run as failed; network failures are still retried as before. A download whose address can't be used is marked failed so the queue moves on.
- **Opening another app** (a trailer, the share sheet, the installer, "allow installs from Tuned") goes through `startActivitySafely`, so a device with nothing to handle it (an Android TV without a browser) logs it instead of crashing.
- **Sign-in and sync never stay stuck on "busy"**: whatever fails is logged and the screen says so. A server address OkHttp can't parse says so instead of crashing.
- **Installing an older release over a newer one** starts the database afresh (`fallbackToDestructiveMigrationOnDowngrade`) instead of crashing on every launch; upgrades always migrate.
- **A real crash still ends the app.** `CrashReporter` saves a report first (stack trace, device, version and the 200 log lines before it) and then hands the crash to Android as before. Keeping a process alive after an unknown failure on the main thread leaves it frozen or half-working, which is worse than a restart. At most 10 reports are kept, on the device only.
- **A wrong date says so** (Melvin, 2026-11-19: "Do 2", the wrong-clock hint from the Horizon Maps review). Cheap TV boxes often lose the date when unplugged, and then every secure connection fails as if the server were down. `DeviceClock` (`diagnostics/DeviceClock.kt`) reads the `Date` header of every answer through the shared HTTP client (plain-HTTP playlist servers still answer when HTTPS fails) and `ClockCheck` (`:core`, tested) calls the clock wrong when it is before 1 November 2026 or more than a day from a server's. Then a failed sign-in, the first download's error and Settings › Guide and refresh's last error add: check the date and time in Android's settings. It is logged once.
- **A damaged settings file resets to the defaults** (DataStore `ReplaceFileCorruptionHandler`), and a read error reads as the defaults, instead of crashing every launch.

## The log

- `AppLog` keeps the last 1,000 lines in memory. Every level is always kept (Melvin, 2026-11-18: "remove developer mode but keep logs"); the detailed debug and info lines come only from sync and casting, so they are few, and their text is built lazily. Debug builds mirror every line to logcat.
- **No login ever reaches the log** ([security](security.md)). Every line goes through `AppLog.scrub`: `LogScrubber` (`:core`, tested) masks Xtream stream paths, `user:pass@` addresses and secret query values (`username`, `password`, `token`, `api_key`, …) without knowing the login, and the login in use is masked on top (set by `CredentialStore` on load and save, cleared on sign-out).

## Settings › Updates and advanced › Logs

- **Logs** is always there, under Troubleshooting: crash reports, then the recent log newest first, with copy, share (plain text, up to 200,000 characters) and clear.
- **Developer mode was removed** (Melvin, 2026-11-18: "remove developer mode but keep logs"). It was a switch that kept the detailed log and showed Logs; now both are always on, so there is nothing to turn on before a problem can be looked into. The old stored `developer_mode` value is simply ignored.

## Report a problem (Melvin, 2026-11-19)

"Do 3", from the Horizon Maps review. Settings › Updates and advanced › Troubleshooting › **Report a problem** gathers what a fix needs in one tap: the version and build, whether it's the GitHub or store copy, the device and Android version, a blank "What happened" space, the latest crash or freeze report and the last 400 log lines (`diagnostics/ProblemReport.kt`). It opens the share sheet, so the person picks where it goes (email, a chat, a note); nothing is sent by Tuned itself, and there is no built-in address. A device with nothing to share to (some TVs) gets it copied instead, and the row says so. Every line has already been through `AppLog.scrub`, so no login is in it. It is capped at 200,000 characters, like Logs' share.

## Device smoke test (2026-11-18)

From the gap review Melvin asked for ("what else are we missing"): the app had JVM tests only, so nothing checked the real screens or the one-connection rule end to end. `:smoketest` installs the github debug app on an emulator (a Gradle managed device, Pixel 6, API 34 ATD image) and drives it with UI Automator against `:fakeprovider`, a stand-in provider on the loopback that serves a generated playlist and a 30 second test clip at about the speed it plays, so a stream connection stays open the way a live channel's does. It clears the app's data, adds the playlist link from the welcome screen, waits for the channels, plays one, changes channel twice with the remote's channel key, and goes back to Live TV. It fails if two stream connections were ever open at the same moment. CI runs it on every pull request as **Device smoke test**, part of "CI passed". No real login or playlist is involved.
