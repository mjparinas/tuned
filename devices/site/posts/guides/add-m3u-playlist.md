---
title: How to add an M3U playlist link or file to Tuned
description: Add any M3U or M3U8 link, with or without a guide link, or open an .m3u file saved on your device.
date: 2026-11-19
kind: guide
updated: 2026-11-26
---

If your provider gave you a link ending in `.m3u` or `.m3u8`, or a playlist file, Tuned plays it. M3U is the most common playlist format in IPTV: a plain list of channels with their names, logos, groups and stream links.

## Add a playlist link

1. On Tuned's first screen, pick **Playlist link**. (Already set up? Go to **Settings › Playlists and account › Playlists › Add a playlist**.)
2. Paste the playlist link. If you leave off `http://`, Tuned adds it.
3. Paste a **guide link** (XMLTV) if your provider gave you one. It's optional; see below.
4. Give it a name if you like, and tap **Add playlist**.

<img src="/blog/img/guides/welcome.webp" alt="Tuned's welcome screen with Playlist link and Playlist file among the four ways to add a playlist" width="270" height="586" loading="lazy">

Tuned downloads the playlist once and keeps it on the device, so Live TV opens instantly from then on and refreshes in the background.

**Tip:** if the link has `get.php` with a username and password in it, it's really an Xtream Codes login. Tuned recognizes it and sets it up that way, which also gives you catch-up. See [adding an Xtream Codes playlist](/blog/guides/add-xtream-codes-playlist/).

<figure><img src="/blog/img/guides/playlist-add-link.webp" alt="Tuned's Playlist link form with the playlist link, an optional guide link and a name" width="270" height="586" loading="lazy"><figcaption>The Playlist link form.</figcaption></figure>

## Do I need a guide link?

Many playlists name their own guide on their first line (`x-tvg-url`, `url-tvg` or `tvg-url`). Tuned reads it while it saves the playlist, so you get the TV guide without typing anything. If your playlist doesn't name one and you have no guide link, the channels still play; there just won't be a "what's on" line.

You can add more guide links later, to fill channels the first guide misses. See [setting up the TV guide](/blog/guides/tv-guide-epg/).

## Add a playlist file

1. Pick **Playlist file** on the first screen or under **Add a playlist**.
2. Choose the `.m3u` file from your device.

Tuned copies the file into the app, so the playlist keeps working even if you move or delete the original. A file that isn't an M3U playlist is refused rather than half loaded. Because a file belongs to one device, it isn't copied to your other devices by the Tuned account backup.

<figure><img src="/blog/img/guides/settings-page-guide.webp" alt="Tuned's Guide and refresh settings, with Extra guide links" width="270" height="586" loading="lazy"><figcaption>Add more guide links in Settings › Guide and refresh.</figcaption></figure>

## What Tuned reads from your playlist

- **Groups** become the chips on Live TV, Movies and Shows.
- **Films and episodes** are sorted out of the list by their links and names, and episodes are numbered by season.
- **Countries** are worked out from tags like `US|` or `UK:`, so you can hide the ones you don't want in Settings › Channels and lists.
- **Catch-up** attributes (`catchup`, `catchup-days`, `catchup-source`) are understood the way Kodi and other players read them. See [catch-up](/blog/guides/catch-up/).
- **Per-channel user agents and referrers** (`#EXTVLCOPT` lines, or `|User-Agent=` after a link) are sent with that channel's stream.

## If it doesn't load

- Open the link in a browser on the same network. If it downloads a text file starting with `#EXTM3U`, the link works.
- Some servers only answer certain players. Try another [user agent](/blog/guides/user-agent/).
- An error page from the server never replaces a working playlist: Tuned keeps the last good one.
