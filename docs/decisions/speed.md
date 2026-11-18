# Speed

Melvin (2026-11-17): "prioritize speed and efficiency when using the app."

- **Open to content.** The first screen reads the cached playlist; nothing waits on the network. The splash holds only until settings load (milliseconds). The database, HTTP client and image loader are built on first use.
- **Paged lists.** Live TV, Movies and Shows page straight from Room (60 per page, placeholders on), so tens of thousands of streams scroll smoothly and search is a single full-text index lookup ([playlist](playlist.md)).
- **Streaming imports.** Playlists and guides are parsed line by line and inserted in batches of short transactions; memory stays flat whatever the size, and lists and search never wait on a refresh: both files are written beside the cache and brought in at the end (see [playlist](playlist.md)).
- **Instant taps.** Favourite and Watch later show the change the moment they're tapped and save behind the scenes (`data/Picks.kt`), so a busy database never makes a heart lag.
- **Images.** Coil with a 20% memory cache and a disk cache of 5% of the storage (512 MB to 2 GB) in app storage, through the shared OkHttp client. A picture seen once loads from the device after that, and TMDB details show from the database before any refresh ([offline](offline.md)).
- **Player start.** Fast-start TS extractor flags, a Fast start buffer mode, and Media3's own PlayerView.
- **Release builds** are shrunk with R8 and ship a baseline profile installer (profileinstaller).

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
