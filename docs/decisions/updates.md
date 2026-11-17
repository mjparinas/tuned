# Updates

Melvin first chose **Google Play in-app updates** (2026-11-17), then **GitHub Releases for now** (2026-11-17, "create github releases for now") so he can install real builds before the app is on Play. Both paths stay in the code; which one a copy uses depends on who installed it.

## GitHub Releases (now)

- `release.yml` runs on every promotion to `main` and by hand. It builds a signed APK and publishes a GitHub release tagged `v<version>` (for example `v1.2.0`), with `tuned.apk` attached. There is no build number in the tag or title (Melvin, 2026-11-17: "get rid of build and just bump major minor patch"); releases before that were tagged `v<version>-build.<run>`, and the updater and `next-version.mjs` still read those. The version name is semver, bumped from the commits since the last release by `scripts/next-version.mjs` (Melvin, 2026-11-17): `feat:` titles bump the minor, everything else the patch, `!` or `BREAKING CHANGE` the major, and `package.json` is a floor for a hand bump. The version code is made from the version, major × 1,000,000 + minor × 1,000 + patch (1.2.3 is 1002003), so each release is newer than the last for Android and the updater, and it is far above the run numbers the old tags used. Minor and patch stay below 1,000. Copies installed from a `-build` release can't read the plain tags, so they need the first plain release installed by hand once.
- A copy that Play didn't install asks GitHub for the latest release (`updates/GitHubUpdates.kt`) each time Tuned comes to the front, at most once an hour, and after each background refresh. Checking only on a cold start missed releases entirely while Tuned stayed open in the background (Melvin, 2026-11-17: "when a new release is up, i dont see it prompt in the app"). When the background refresh finds one, a notification says so. If its version code is higher, the APK downloads in the background, and a small "ready to install" bar appears on the home screen. One tap hands it to Android's installer, which asks to confirm. The first time, Android also asks to allow Tuned to install apps.
- Why it never seemed to work before 1.2.0 (Melvin, 2026-11-17: "the new update is available functionality never works"): up to 1.1.0 Tuned only looked on a cold start, which a phone rarely does, and 1.1.0 couldn't read the plain `v1.2.0` tag, so every one of those updates had to be installed by hand. 1.2.0 is the first copy that finds the next release itself. The check also runs in the app's scope (closing the screen mid-check left Settings stuck on "Checking"), tries again a few minutes after a failed check instead of an hour, resumes a dropped APK download, and throws away a download whose size or SHA-256 doesn't match what GitHub lists. `GitHubUpdatesTest` runs the whole path against GitHub's real answer for this repository.
- Settings › Check for updates asks GitHub straight away, whatever the automatic setting, and says what it found (up to date, couldn't reach GitHub, or ready with an Install button).
- Android only installs the update if it is signed with the same key as the installed app, so a swapped download can't replace Tuned. **Keep the upload keystore safe**: without it, installed copies can never update again.
- Settings › Advanced › Check for updates turns this off. Debug builds never check.
- The app needs `REQUEST_INSTALL_PACKAGES` for this. Play doesn't allow that permission, so a Play build must drop it.

## Google Play (later)

`release.yml` also builds the App Bundle and uploads it to the Play internal track once `PLAY_SERVICE_ACCOUNT_JSON` exists, with in-app update priority 5. Copies installed from Play ask Play on each start and update straight away (an immediate update), or, where Play allows only a flexible update, download in the background and restart into it. The very first Play upload has to be done by hand in Play Console.

## Signing secrets

- `ANDROID_KEYSTORE_B64`: the upload keystore (alias `upload`), base64.
- `ANDROID_KEYSTORE_PASSWORD`: its password, which is also the key's password.

Until both exist, `release.yml` skips with a notice.
