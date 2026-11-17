# Playlist and guide

**Source.** Xtream Codes-style servers: `get.php?type=m3u_plus&output=ts` for the playlist and `xmltv.php` for the guide, both built from the server, username and password (`XtreamUrls`). The default server is `http://eros4k.ddns.net`, editable at sign-in and in Settings (Melvin, 2026-11-17). Sign-in also takes a plain M3U link and XMLTV link instead, for other providers.

**Sections.** Xtream URLs carry the section in the path (`/live/`, `/movie/`, `/series/`), which decides Live TV, Movies or Shows. Without it, the category name decides (`VOD`, `Movies`, `Series`…), then the file type (`.mkv`, `.mp4` are movies), and anything else is live.

**Shows.** Episodes are grouped by the show name read from the title (`Show S01 E02`, `Show S01E02`, `Show 1x02`); a title without numbering is one episode of its own show, so nothing disappears.

**Countries** (Melvin, 2026-11-17: "toggle controls in the settings that allow you to hide certain countries' content"). Providers seldom fill `tvg-country`, so the region comes from the category prefix: `US| News`, `UK: Sport`, `|CA| Kids`, `[FR] Cinéma`, `4K | US Movies` (quality tags are skipped). Settings › Countries lists every region found, with how many streams each has; turning one off hides it in every list and search. Streams with no region always show. _To revisit once we've seen the real eros4k playlist's categories._

**Adult categories** are hidden by default (Settings › Content › Show adult categories).

**Downloading (2026-11-17, after v0.1.0 failed to sign in with "Software caused connection abort").** Melvin's provider serves playlists of about 400 MB from slow servers. So each file is first saved to disk by `Downloader` and only then read into the database:

- Timeouts are long (30 s to connect, 3 minutes between bytes), since slow panels can take minutes to start answering.
- A dropped connection is retried with backoff (2 s up to 30 s). Only attempts that make no progress count, and it gives up after six in a row.
- It resumes with HTTP Range only when the server names a validator (ETag or Last-Modified, sent back as If-Range), so a changed file is never stitched onto an old one. Otherwise it starts over.
- The body is saved as sent (gzip stays gzip) and unzipped while reading.
- The refresh runs in a WorkManager worker that is promoted to a foreground service, so it keeps going when the screen turns off or another app comes forward. The connection abort came from Android cutting a long download that ran in the sign-in screen.
- Sign-in now only checks the login with `player_api.php` (a small answer) and then opens the app. The first screen shows a progress bar (MB of MB, then items saved), as does the notification. A thin bar above the lists shows later refreshes.

**Caching.** Each refresh reads the downloaded file into Room in batches of 1,000 inside one transaction. The old data stays visible until the new data commits (WAL), and a failed or empty download rolls back, so the cache is never left half-written or emptied. Favourites and watch history are keyed by a stable id (the stream id from the URL, the section and the name), so they survive refreshes and a change of login.

**Guide.** Only programmes for channels in the playlist, from an hour ago to _Guide days to keep_ ahead (3 by default), are stored. Gzipped guides are unwrapped by their magic bytes.

**Background refresh.** WorkManager, periodic, with a network and not on low battery (optionally Wi-Fi only): channels and movies every 12 hours, the guide every 6, both editable (or off). Pull to refresh and Settings › Refresh now run it at once.

## Guide reminders (2026-11-17)

A bell on each upcoming programme in a channel's guide sets a reminder, a few minutes before it starts (Settings › Background, default 5 minutes). Each reminder is a WorkManager job tagged with the channel and start time, so it survives restarts and needs no table; tapping the notification plays the channel. Android 13 and later ask for the notification permission the first time a bell is tapped, and a "no" leaves the reminder unset. A reminder more than 30 minutes late says nothing, and signing out cancels them all.
