# Speed

Melvin (2026-11-17): "prioritize speed and efficiency when using the app."

- **Open to content.** The first screen reads the cached playlist; nothing waits on the network. The splash holds only until settings load (milliseconds). The database, HTTP client and image loader are built on first use.
- **Paged lists.** Live TV, Movies and Shows page straight from Room (60 per page, placeholders on), so tens of thousands of streams scroll smoothly and search is a single full-text index lookup ([playlist](playlist.md)).
- **Streaming imports.** Playlists and guides are parsed line by line and inserted in batches of short transactions; memory stays flat whatever the size, and lists and search never wait on a refresh: both files are written beside the cache and brought in at the end (see [playlist](playlist.md)).
- **Instant taps.** Favourite and Watch later show the change the moment they're tapped and save behind the scenes (`data/Picks.kt`), so a busy database never makes a heart lag.
- **Images.** Coil with a 20% memory cache and a disk cache of 5% of the storage (512 MB to 2 GB) in app storage, through the shared OkHttp client. A picture seen once loads from the device after that, and TMDB details show from the database before any refresh ([offline](offline.md)).
- **Player start.** Fast-start TS extractor flags, a Fast start buffer mode, and Media3's own PlayerView.
- **Release builds** are shrunk with R8 and ship a **baseline profile** (2026-11-18, from the gap review Melvin asked for: "what else are we missing"). Before it, the app had the profile installer but no profile, so every cold start ran its own code interpreted until Android compiled it in the background days later. The `:baselineprofile` module drives the release app on an emulator through the first minute (cold start to the cached Live TV list, the three tabs, opening a channel) against a fake provider on the loopback, so no real login is involved, and writes `app/src/main/generated/baselineProfiles/`, which is committed and used by both the GitHub and store builds (it is recorded on the GitHub build). A startup profile from the same run also orders the dex file. The **Baseline profile** workflow regenerates it and measures cold start without and with the profile (median of 10 cold starts each, in the run's summary). It runs by hand or when the journeys change, and is not part of "CI passed" because an emulator run takes about half an hour. First measurement (2026-11-18, CI emulator, Pixel 6 image on API 34 with software graphics, median of 10 cold starts to the first frame): **1,541 ms without the profile, 1,437 ms with it, 7% faster**. The emulator only shows the direction; a real phone should gain more, because it reads the dex file from slower storage. Regenerate it after a change to what opens first.

## Quiet start (Melvin, 2026-11-20: 2.2.0 "took awhile for the app to get to the profile screen, maybe like 20/30 seconds")

On his TV, "Who's watching?" stayed blank while the database was busy: it drew nothing until Room listed the profiles, and a cold start (above all the first after an update, when Android runs overdue work at once) had the backup, 2.2.0's channel list for the website, a scheduled playlist or guide refresh, downloads and the TV home screen's row all reading and writing it at the same moment, on a slow box. Two changes, so the first screen never waits on background work:

- **The picker draws from a saved copy.** The profiles are kept with the settings (`profiles_seen`, this device only; `data/ProfilesSeen.kt`) each time they change, and the splash already waits for the settings, so "Who's watching?" draws with its first frame. The database's list replaces it as soon as it answers.
- **Background work waits for the first screen** (`work/QuietStart.kt`). The backup and the channel list it shares, playlist and guide refreshes, downloads, channel scans, the TV home screen's row, the app icon's shortcuts, the update check and the region bookkeeping start 3 seconds after Home first draws, or 20 seconds after the app started for someone sitting on "Who's watching?" or the welcome screen, or 2 seconds after a start that brings up no screen (Android started the app only for background work). A refresh or scan the person asks for starts at once. Nothing is skipped: the work runs as it always did, a few seconds later.

## Lighter mode for weak TV boxes (Melvin, 2026-11-19)

From the Horizon Maps review ("Do 1, 2, 3, 4, 5, 6", idea 5). Cheap Android TV boxes have little memory and slow graphics, and a fade on every poster or a pulsing placeholder is enough to make them stutter or freeze. **Settings › Appearance › Lighter mode** is Automatic (the default), On or Off, and stays on the device rather than syncing, because the phone and the TV box differ. When it is on:

- Pictures load without a fade, are held in half the memory (RGB 565 where they have no transparency), and the picture memory cache is 10% of the app's memory instead of 20%.
- Loading placeholders stay still instead of pulsing, and the Premium avatar ring doesn't turn.

**Automatic** turns it on when Android calls the device low on memory, or after two freezes within a week (the freeze reports from [diagnostics](diagnostics.md); `LighterRule` in `:core`, tested). One freeze can be bad luck; two in a week is the device. Clearing Logs clears the freeze reports, so Automatic starts over. `Lighter` (`diagnostics/Lighter.kt`) keeps its answer on the device, because the picture loader is built before the settings load: a change reaches the pictures on the next start and everything else at once. The setting's summary says when Automatic has turned it on.

## Scroll bar on long lists (Melvin, 2026-11-18)

Melvin: "since there are so many channels, on the live tv page, moves and shows page, i dont see any scroll bars on the right side indicating how much left i can scroll".

- Live TV, Movies and Shows have a scroll bar down the right (`ui/common/FastScrollbar.kt`, maths in `core/FastScroll.kt`). It shows while the list moves and fades 1.2 seconds after; Settings › Channels and lists › Always show the scroll bar keeps it on (off by default, so the list stays clean).
- The thumb is a fixed 48 dp, not sized to the share on screen, because with 40,000 channels that share would be a sliver. Its place is how far down the top row is.
- Dragging the thumb jumps straight to the row under the finger, so a long list is crossed in one move. A bubble beside it names the category there (playlists list streams category by category), with the channel number in front on Live TV when numbers are on. The bubble reads only rows already loaded (`peekOrNull`), so dragging never asks the database for more than the jump itself; Room's paging with placeholders loads just the page landed on.
- Only a list longer than three screens gets one. Only the visible thumb takes a finger, so the hearts and guide buttons at the right of each row stay tappable while it's hidden.
- Speed: the thumb's position and fade are read in layout and draw only, so scrolling doesn't recompose the bar. On a TV it shows while the D-pad scrolls and never takes focus.
- The grid guide keeps its own scrolling and has no bar.

## Speed audit (Melvin, 2026-11-17: "speed for all functions in the app is important")

Measured on a copy the size of a big playlist (40,000 channels, 90,000 films, 350,000 episodes in 14,000 shows, 480,000 programmes), on a desktop CPU; a phone is roughly 3 to 5 times slower. Room's paged lists run a count and then a page, so a tab opening costs both.

- **Live TV** no longer joins every programme and groups the result: what's on now is one indexed lookup per channel (`NOW_JOIN` in `Daos.kt`), and Favourites and Recent are their own queries, so the main list reads straight down the `(kind, sortOrder)` index. Count: 1,899 ms to 45 ms. First page: 108 ms to 1 ms. Favourite taps no longer reload the list.
- **Shows** browse a per-show table (`show_groups`, rebuilt after each refresh) instead of grouping every episode: count 325 ms to 12 ms, a page 263 ms to 14 ms. A search or the Favourites filter still reads episodes, which the full-text index keeps quick.
- **Upkeep.** `PRAGMA optimize` after each refresh keeps SQLite's index choices current. No scheduled VACUUM: each refresh's side tables reuse the free pages the last one left, so the file doesn't keep growing, and a VACUUM would hold the database for seconds.
- **Perceived speed.** Lists show pulsing grey rows and posters while their first page loads, instead of a blank. Favourite and Watch later taps show at once.
- **Guard rails.** Debug builds turn on StrictMode, logging any disk or network work on the main thread.
