# Diagnostics: crashes, errors and the developer log

Melvin, 2026-11-17: "optimize for the app not crashing or being unreliable", and for anything unhandled, "a developers mode or debug mode in the settings where I can see the logs, and then I can use that to fix the app."

## What happens to a failure

- **Background work never crashes the app.** `AppContainer.appScope` carries a `CoroutineExceptionHandler` that logs. ViewModels and activities launch fire-and-forget work with `launchSafely(tag) { … }` (`diagnostics/Safely.kt`), which logs a failure and leaves the screen working. Work whose result the person waits for still handles its errors itself and says what went wrong (sign-in, backup, refresh).
- **`attempt(tag, what) { … }`** replaces `runCatching` where the failure is worth knowing about. Unlike `runCatching` it never swallows `CancellationException`, so a cancelled coroutine stops instead of carrying on.
- **A real crash still ends the app.** `CrashReporter` saves a report first (stack trace, device, version and the 200 log lines before it) and then hands the crash to Android as before. Keeping a process alive after an unknown failure on the main thread leaves it frozen or half-working, which is worse than a restart. At most 10 reports are kept, on the device only.
- **A damaged settings file resets to the defaults** (DataStore `ReplaceFileCorruptionHandler`), and a read error reads as the defaults, instead of crashing every launch.

## The log

- `AppLog` keeps the last 1,000 lines in memory. Warnings and errors are always kept; debug and info lines only in developer mode, built lazily so they cost nothing otherwise. Debug builds mirror every line to logcat.
- **No login ever reaches the log** ([security](security.md)). Every line goes through `AppLog.scrub`: `LogScrubber` (`:core`, tested) masks Xtream stream paths, `user:pass@` addresses and secret query values (`username`, `password`, `token`, `api_key`, …) without knowing the login, and the login in use is masked on top (set by `CredentialStore` on load and save, cleared on sign-out).

## Settings › Developer

- **Developer mode** (off by default, kept on this device only, not in backups) keeps the detailed log and shows **Logs**: crash reports, then the recent log newest first, with copy, share (plain text, up to 200,000 characters) and clear. Crash reports are saved whether or not it's on.
