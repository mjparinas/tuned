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
- **A damaged settings file resets to the defaults** (DataStore `ReplaceFileCorruptionHandler`), and a read error reads as the defaults, instead of crashing every launch.

## The log

- `AppLog` keeps the last 1,000 lines in memory. Every level is always kept (Melvin, 2026-11-18: "remove developer mode but keep logs"); the detailed debug and info lines come only from sync and casting, so they are few, and their text is built lazily. Debug builds mirror every line to logcat.
- **No login ever reaches the log** ([security](security.md)). Every line goes through `AppLog.scrub`: `LogScrubber` (`:core`, tested) masks Xtream stream paths, `user:pass@` addresses and secret query values (`username`, `password`, `token`, `api_key`, …) without knowing the login, and the login in use is masked on top (set by `CredentialStore` on load and save, cleared on sign-out).

## Settings › Updates and advanced › Logs

- **Logs** is always there, under Troubleshooting: crash reports, then the recent log newest first, with copy, share (plain text, up to 200,000 characters) and clear.
- **Developer mode was removed** (Melvin, 2026-11-18: "remove developer mode but keep logs"). It was a switch that kept the detailed log and showed Logs; now both are always on, so there is nothing to turn on before a problem can be looked into. The old stored `developer_mode` value is simply ignored.
