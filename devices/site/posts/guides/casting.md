---
title: How to cast from Tuned to a Chromecast or smart TV
seoTitle: "How to cast IPTV from Tuned to a Chromecast or smart TV"
description: Send live TV, films and episodes to a Chromecast, a TV with Chromecast built in, or a DLNA TV, including what you downloaded.
date: 2026-11-19
kind: guide
---

Tuned casts to **Chromecast** devices (including TVs and boxes with Chromecast built in) and to **DLNA and UPnP** smart TVs on your Wi-Fi.

## Start casting

1. Play something in Tuned.
2. Tap the screen to show the controls, then tap the **cast** button in the top row.
3. Pick your TV. Chromecast and DLNA TVs are listed together.

The video shows "Connecting to…", then "Starting on…", then "Playing on…", so you can see it working while the TV's app opens.

<img src="/blog/img/guides/player-casting.webp" alt="Tuned's player while casting, showing Playing on SHIELD with the time and seek bar of the film on the TV" width="640" height="295" loading="lazy">

## Your phone becomes the remote

While casting, your phone stops its own stream, so your provider only ever sees one connection. The controls drive the TV: play and pause, the seek bar and skip buttons for films and episodes, and next and previous to change channel or episode. Anything that can't work on the TV (subtitle timing, quality, record, fit) is greyed out rather than hidden.

- **Leaving the app** (Home, locking the phone) keeps the TV playing. Open the player again to pick it back up.
- **Backing out of the player** ends the cast and closes the app on the TV.

## Casting downloaded films and episodes

A TV can't open a file on your phone, so Tuned serves it over your Wi-Fi while the player stays open. For a Chromecast, it turns the file into a stream the TV can play as it goes, so it starts at once with no waiting. Surround sound goes to a Chromecast as stereo. Your phone and TV need to be on the same Wi-Fi.

## Subtitles on the TV

Subtitles you found with [subtitle search](/blog/guides/subtitles/) go to a Chromecast with the video, and the CC button turns them on and off on the TV. Subtitles inside a provider's stream or inside an MKV file can't be sent.

## If it won't play

- **The TV turns it away.** Some providers allow one connection only. Tuned closes the phone's stream before sending the link and tries once more after 3 seconds, which fixes most of these.
- **Films in MKV from your provider** may not play on a Chromecast, whose player doesn't read every format. DLNA TVs usually play them.
- **Live channels on Xtream** are sent to a Chromecast as the channel's HLS link, which it can play.
- Settings › Updates and advanced › Logs records every step of a cast, for a [problem report](/blog/guides/provider-errors/).

Downloads and channel scans wait while you cast, and carry on when the cast ends.
