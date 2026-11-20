---
title: How to change the user agent in Tuned
description: When a server refuses to play or load your playlist, sending another player's user agent often fixes it. Here's how.
date: 2026-11-19
kind: guide
---

Every time an app asks a server for something, it says what it is. That's the **user agent**. Some IPTV servers only answer players they know, and turn others away with an error, an empty playlist or a stream that won't start. Telling the server Tuned is a player it knows often fixes it.

## Change it

1. Go to **Settings › Updates and advanced › User agent**.
2. Pick one from the list. Each shows the exact text it sends under its name.
3. Go back and try the playlist or channel again. A **Refresh now** in Settings › Guide and refresh reloads the playlist with it.

<img src="/blog/img/guides/user-agents.webp" alt="Tuned's user agent list: Tuned, VLC, TiviMate, IPTV Smarters, OTT Navigator, Kodi, MAG box, ExoPlayer, Chrome and Custom" width="270" height="586" loading="lazy">

The choices are **Tuned** (the default), **VLC**, **TiviMate**, **IPTV Smarters**, **OTT Navigator**, **Kodi**, **MAG box** (for Stalker portal boxes), **ExoPlayer** and **Chrome**, then **Custom…** to type your own.

## Which one should I pick?

- Your provider names an app it supports? Pick that one.
- A MAC portal? Tuned already sends a MAG box's user agent to portals.
- Not sure? Try **VLC**, then **TiviMate**, then **IPTV Smarters**.
- Your provider gave you an exact string? Use **Custom…** and paste it.

## Per-channel user agents

Some playlists set a user agent (or referrer) for particular channels, with `#EXTVLCOPT:http-user-agent=` lines or `|User-Agent=` after a link. Tuned always sends those for that channel, whatever you pick here, and channel scans and downloads send them too.

## Casting

A TV you cast to plays the link itself and sends its own user agent, so a channel that needs a special one may play on your phone but not on the TV.

Still not working? See [provider errors](/blog/guides/provider-errors/).
