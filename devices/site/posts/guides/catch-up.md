---
title: How to watch catch-up TV in Tuned
description: Replay programmes from the past few days on channels your provider records, from the guide or the grid.
date: 2026-11-19
kind: guide
---

Catch-up (also called TV archive or timeshift) lets you play a programme that has already aired. Your provider records some channels for a few days, and Tuned shows a replay button on anything still in that window.

## Which channels have catch-up?

It depends on your provider. Tuned asks:

- **Xtream Codes logins:** each refresh asks the server which channels keep recordings and for how many days (Tuned shows up to 7).
- **M3U playlists:** Tuned reads the playlist's catch-up attributes (`catchup`, `catchup-days`, `catchup-source`, or `timeshift`), on each channel or once for the whole playlist. It understands the common modes (`default`, `append`, `shift`, `flussonic` and `xc`), the same way Kodi's IPTV Simple Client reads them. A channel that says it has catch-up but not for how long gets 5 days.

Channels without catch-up keep only the last hour of their guide; channels with it keep as many past days as the server records.

## Play something from earlier

1. On Live TV, open a channel's guide.
2. Scroll up to an earlier programme. If it's still recorded, it has a **replay** button.
3. Tap it. It plays from the start.

In the [grid guide](/blog/guides/tv-guide-epg/) you can scroll back as far as the longest catch-up any channel keeps; tap a past programme to open its channel's guide and the replay button.

A catch-up programme plays like a film: pause, skip and seek all work. It doesn't have next and previous, and it isn't added to Continue Watching.

## Times look wrong?

Catch-up links use your provider's time zone, which Tuned reads from the server. If a replay starts in the wrong place, check the [guide time offset](/blog/guides/tv-guide-epg/) in Settings › Guide and refresh.

## Catch-up versus pause and rewind

Catch-up is your provider's recording, so it reaches days back. **Pause and rewind live TV** is Tuned keeping the last 30 minutes (15, 60 or off, in Settings › Playback) of the channel you're watching, so you can pause and go back on any channel, with or without catch-up. In the Google Play version it's part of [Premium](/blog/guides/premium/).
