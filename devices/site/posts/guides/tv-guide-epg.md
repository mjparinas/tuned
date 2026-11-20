---
title: How to set up the TV guide (EPG) in Tuned
seoTitle: "How to set up an IPTV TV guide (EPG) in Tuned"
description: Get what's on now and next on every channel, the full grid guide, reminders, and fixes for channels with the wrong times.
date: 2026-11-19
kind: guide
---

The TV guide, or EPG (electronic programme guide), is what tells you what's on now and next. In IPTV it comes as an **XMLTV** file from your provider, separate from the channel list. Tuned downloads it with your playlist, keeps it on the device, and shows it everywhere: under each channel, in the player, in a full grid, and on the Live Events page of today's games and events.

## Where the guide comes from

- **Xtream Codes logins** get the guide from the same server automatically.
- **MAC portals** get it from the portal, up to a week ahead.
- **M3U links and files** use the guide link you gave when adding them, or the one the playlist names on its first line. Without either, there's no guide yet; see "Add more guide links" below.

## Two ways to see it

**The list.** Live TV shows each channel with what's on now and a bar for how far through it is. Tap the list button at the end of a channel's row for its page and full schedule.

<img src="/blog/img/guides/channel-guide.webp" alt="A channel's guide in Tuned, with what's on now highlighted and a reminder bell on each upcoming programme" width="270" height="586" loading="lazy">

**The grid.** Tap the grid button beside the search box on Live TV (on a TV, **Show the TV guide grid**) to see channels down the side and time across the top, with a line at the current time. Tap what's on now to watch it; tap anything else to open that channel's guide. To always start in the grid, turn on **Settings › Channels and lists › Show Live TV as a guide grid**.

The grid works with your groups, favourites and search, and it only reads the guide for the rows on screen, so it stays quick with thousands of channels.

## Reminders

Tap the bell beside an upcoming programme. Tuned sends a notification a few minutes before it starts (5 by default, changeable in **Settings › Guide and refresh**), and tapping it plays the channel.

## Settings › Guide and refresh

- **Refresh now** downloads the playlist and guide straight away.
- **How often**: the guide refreshes every 6 hours by default, channels every 12. You can change either, or turn them off.
- **Wi-Fi only** keeps refreshes (and downloads) off mobile data.
- **Guide days**: how many days ahead to keep.
- **Time zone**: guide times show in your phone's time zone unless you pick another.
- **Guide time offset** moves the whole guide by half an hour or by whole hours, up to 12 either way, for a provider whose guide is consistently off.

## Add more guide links

**Settings › Guide and refresh › Extra guide links** takes more XMLTV links. They're read after your provider's guide and only fill channels it left empty, so they never overwrite good data. They're stored encrypted, since such links often carry a key.

## Fix one channel

If one channel shows the wrong times or the wrong programmes, open its guide and tap the **calendar** button. You can:

- move just that channel's times, and
- take its programmes from another entry in the guide (handy when a channel's guide ID doesn't match).

The fix stays through every refresh.

Still blank? See [the TV guide is empty](/blog/guides/blank-tv-guide/).
