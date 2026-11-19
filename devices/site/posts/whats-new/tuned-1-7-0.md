---
title: What's new in Tuned 1.7.0
description: "Tuned 1.7 adds catch-up for plain M3U playlists, media buttons and the Assistant in the player, and makes downloads always wait while you watch."
date: 2026-11-18
kind: release
version: 1.7.0
---

Tuned 1.7 adds catch-up for plain M3U playlists, media buttons and the Assistant in the player, and makes downloads always wait while you watch.

## New

- Cleaner player title, subtitle buttons beside CC, logos in Channels
- Catch-up for plain M3U playlists
- Media buttons and the Assistant control the player

## Fixed

- Sharp channel logos in Movies and read a stream's own country tag first
- Keep half-downloaded films when the player opens, and never download while it plays
- Make hidden countries stay hidden (category wins, native names, Nordic) and clean grid titles
- A channel picked while the player is open plays instead of being ignored
- A running download no longer reads as every connection in use elsewhere
- Start on the last channel waits for Who's watching
- A hidden channel opened from Favourites or Search plays itself, not its category's first
- Downloads wait while anything is watched, casting included, and hang up before the stream opens
