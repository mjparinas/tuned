# Playlist and guide

**Source.** Xtream Codes-style servers: `get.php?type=m3u_plus&output=ts` for the playlist and `xmltv.php` for the guide, both built from the server, username and password (`XtreamUrls`). The default server is `http://eros4k.ddns.net`, editable at sign-in and in Settings (Melvin, 2026-11-17). Sign-in also takes a plain M3U link and XMLTV link instead, for other providers.

**Sections.** Xtream URLs carry the section in the path (`/live/`, `/movie/`, `/series/`), which decides Live TV, Movies or Shows. Without it, the category name decides (`VOD`, `Movies`, `Series`…), then the file type (`.mkv`, `.mp4` are movies), and anything else is live.

**Shows.** Episodes are grouped by the show name read from the title (`Show S01 E02`, `Show S01E02`, `Show 1x02`); a title without numbering is one episode of its own show, so nothing disappears.

**Countries** (Melvin, 2026-11-17: "toggle controls in the settings that allow you to hide certain countries' content"). Providers seldom fill `tvg-country`, so the region comes from the category prefix: `US| News`, `UK: Sport`, `|CA| Kids`, `[FR] Cinéma`, `4K | US Movies` (quality tags are skipped). Settings › Countries lists every region found, with how many streams each has; turning one off hides it in every list and search. Streams with no region always show. _To revisit once we've seen the real eros4k playlist's categories._

**Adult categories** are hidden by default (Settings › Content › Show adult categories).

**Caching.** Each refresh streams the download into Room in batches of 1,000 inside one transaction. The old data stays visible until the new data commits (WAL), and a failed or empty download rolls back, so the cache is never left half-written or emptied. Favourites and watch history are keyed by a stable id (the stream id from the URL, the section and the name), so they survive refreshes and a change of login.

**Guide.** Only programmes for channels in the playlist, from an hour ago to _Guide days to keep_ ahead (3 by default), are stored. Gzipped guides are unwrapped by their magic bytes.

**Background refresh.** WorkManager, periodic, with a network and not on low battery (optionally Wi-Fi only): channels and movies every 12 hours, the guide every 6, both editable (or off). Pull to refresh and Settings › Refresh now run it at once.
