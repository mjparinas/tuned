# Speed

Melvin (2026-11-17): "prioritize speed and efficiency when using the app."

- **Open to content.** The first screen reads the cached playlist; nothing waits on the network. The splash holds only until settings load (milliseconds). The database, HTTP client and image loader are built on first use.
- **Paged lists.** Live TV, Movies and Shows page straight from Room (60 per page, placeholders on), so tens of thousands of streams scroll smoothly and search is a single full-text index lookup ([playlist](playlist.md)).
- **Streaming imports.** Playlists and guides are parsed line by line and inserted in batches of short transactions; memory stays flat whatever the size, and lists and search never wait on a refresh: both files are written beside the cache and brought in at the end (see [playlist](playlist.md)).
- **Instant taps.** Favourite and Watch later show the change the moment they're tapped and save behind the scenes (`data/Picks.kt`), so a busy database never makes a heart lag.
- **Images.** Coil with a 20% memory cache and a 256 MB disk cache, through the shared OkHttp client.
- **Player start.** Fast-start TS extractor flags, a Fast start buffer mode, and Media3's own PlayerView.
- **Release builds** are shrunk with R8 and ship a baseline profile installer (profileinstaller).

## Speed audit (Melvin, 2026-11-17: "speed for all functions in the app is important")

Measured on a copy the size of a big playlist (40,000 channels, 90,000 films, 350,000 episodes in 14,000 shows, 480,000 programmes), on a desktop CPU; a phone is roughly 3 to 5 times slower. Room's paged lists run a count and then a page, so a tab opening costs both.

- **Live TV** no longer joins every programme and groups the result: what's on now is one indexed lookup per channel (`NOW_JOIN` in `Daos.kt`), and Favourites and Recent are their own queries, so the main list reads straight down the `(kind, sortOrder)` index. Count: 1,899 ms to 45 ms. First page: 108 ms to 1 ms. Favourite taps no longer reload the list.
- **Shows** browse a per-show table (`show_groups`, rebuilt after each refresh) instead of grouping every episode: count 325 ms to 12 ms, a page 263 ms to 14 ms. A search or the Favourites filter still reads episodes, which the full-text index keeps quick.
- **Upkeep.** `PRAGMA optimize` after each refresh keeps SQLite's index choices current. No scheduled VACUUM: each refresh's side tables reuse the free pages the last one left, so the file doesn't keep growing, and a VACUUM would hold the database for seconds.
- **Perceived speed.** Lists show pulsing grey rows and posters while their first page loads, instead of a blank. Favourite and Watch later taps show at once.
- **Guard rails.** Debug builds turn on StrictMode, logging any disk or network work on the main thread.
