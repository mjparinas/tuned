# Updates

Melvin first chose **Google Play in-app updates** (2026-11-17), then **GitHub Releases for now** (2026-11-17, "create github releases for now") so he can install real builds before the app is on Play. Both paths stay in the code; which one a copy uses depends on who installed it.

## GitHub Releases (now)

- `release.yml` runs on every promotion to `main` and by hand. It builds a signed APK and publishes a GitHub release tagged `v<version>-build.<run number>` (for example `v0.1.0-build.12`), with `tuned.apk` attached. The version name comes from `package.json` and the version code is the run number.
- On each start, a copy that Play didn't install asks GitHub for the latest release (`updates/GitHubUpdates.kt`). If its version code is higher, the APK downloads in the background, and a small "ready to install" bar appears on the home screen. One tap hands it to Android's installer, which asks to confirm. The first time, Android also asks to allow Tuned to install apps.
- Android only installs the update if it is signed with the same key as the installed app, so a swapped download can't replace Tuned. **Keep the upload keystore safe**: without it, installed copies can never update again.
- Settings › Advanced › Check for updates turns this off. Debug builds never check.
- The app needs `REQUEST_INSTALL_PACKAGES` for this. Play doesn't allow that permission, so a Play build must drop it.

## Google Play (later)

`release.yml` also builds the App Bundle and uploads it to the Play internal track once `PLAY_SERVICE_ACCOUNT_JSON` exists, with in-app update priority 5. Copies installed from Play ask Play on each start and update straight away (an immediate update), or, where Play allows only a flexible update, download in the background and restart into it. The very first Play upload has to be done by hand in Play Console.

## Signing secrets

- `ANDROID_KEYSTORE_B64`: the upload keystore (alias `upload`), base64.
- `ANDROID_KEYSTORE_PASSWORD`: its password, which is also the key's password.

Until both exist, `release.yml` skips with a notice.
