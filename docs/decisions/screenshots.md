# Screenshots

Melvin, 2026-11-18: "is there a quick way to get screenshots in this app without building it since it's super slow", and "i would like to see screenshots for all new features posted in these chats".

## How they are made

- **Drawn on the JVM, not on a device.** Robolectric draws each screen with Compose's real renderer (`GraphicsMode.NATIVE`) and Roborazzi saves it as a PNG. No APK is built, no emulator starts and nothing is installed, so a full set takes minutes instead of the emulator cycle.
- **Real screens, made-up data.** Each picture is a route of the app's own navigation graph (`playlistScreens`, `browseScreens`, `settingsScreens`), drawn with the app's real ViewModels over a database filled with `SampleData`: invented channels, films and shows with drawn artwork. No real playlist, login, stream address, provider, channel brand or film appears ([premium](premium.md): store screenshots come from a made-up demo playlist). The Xtream form is drawn empty, since no build fills in a server (2026-11-19).
- **Where they live.** `app/src/test/kotlin/app/tuned/screenshots/`, one file per area (`BrowseScreenshots`, `SettingsScreenshots`, `PlaylistScreenshots`); every page of Settings is drawn on its own, so a new page shows up without a new line. A phone (411 by 891 dp) by default, a sideways 10-inch tablet where the layout changes, and a 1080p TV (960 by 540 dp, inside its overscan margin) for the welcome screen, Live TV and signing in with a phone. The player is an activity around Media3, not a route, so `PlayerScreenshots` builds its views as `PlayerActivity` does, on a sideways phone, with a stand-in TV for the controls while casting. The home screen widgets aren't screens either: `WidgetScreenshots` has Glance turn them into the RemoteViews a launcher gets and draws every size of both on one picture over a stand-in wallpaper, in the dark and light themes, plus their empty states.
- **Test-only.** Roborazzi and coil-test are `testImplementation`, so nothing of this reaches an APK or the release build.

## Running them

- **Locally:** `./gradlew :app:testGithubDebugUnitTest -Pscreenshots`. The PNGs are in `app/build/outputs/roborazzi`. Ordinary test runs compile the screenshot tests, so a broken one fails CI, but don't run them.
- **On GitHub:** the Screenshots workflow (`.github/workflows/screenshots.yml`). It is not part of CI and never a required check (Melvin, 2026-11-17: "remove the screenshots from the pipeline"). It runs when a pull request has the `screenshots` label (again on each push while the label stays), or from Actions › Screenshots › Run workflow once the workflow is on `main`. It force-pushes the pictures, with a `README.md` that shows them all, to the branch `screenshots/<branch>`, and deletes that branch when the pull request closes. The run also keeps them as an artifact for 14 days and says how long the drawing took.

## What a run costs

Measured 2026-11-19 on GitHub's standard runner: about 7 minutes from start to pictures, of which drawing all 25 screens is about 40 seconds. The rest is setting up and compiling the app, which a pull request that changes app code can't skip. The full CI pipeline with its emulator takes longer, and no APK has to be installed. Locally, with the app already compiled, a run is the drawing plus a few seconds.

**Wait for something only the finished screen has.** A shot waits for its text before drawing; text that a placeholder or another part of the screen already shows (a film that is also under Continue watching) gives a picture of a half-loaded screen.

## Bugs they catch

The first runs found Live TV crashing on open on staging (pinned groups, 2026-11-19): a screen that crashes fails its screenshot, so the run doubles as a quick check that every screen opens.

## Every new feature posts its screenshots

A pull request that adds or changes something a person sees adds or updates its picture in the area's file, adds the `screenshots` label, and once the run finishes posts the pictures of the changed screens in the thread that asked for it. Threads read the PNGs from `screenshots/<branch>` with the GitHub API (`get_file_contents` on that ref).
