---
title: The TV guide is empty or wrong: how to fix it
seoTitle: "IPTV TV guide empty or wrong? How to fix it in Tuned"
description: Why channels show no programmes or the wrong times, and how to fix it with guide links, time zones and per-channel fixes.
date: 2026-11-19
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/blank-tv-guide.jpg" alt="Tuned's Guide and refresh settings on a phone, beside the TV guide grid on a TV" width="1200" height="760"></figure>

Channels play but there's no "what's on", or the times are off? The guide (EPG) is a separate download from the channel list, so it can go wrong on its own. Work through these in order.

## The whole guide is empty

**1. Has it finished downloading?** A big guide takes a minute or two after the channels. **Settings › Guide and refresh** shows when it last updated and, if it failed, why. **Refresh now** tries again.

**2. Does your playlist have a guide?**

- **Xtream Codes and MAC portals** bring their own guide.
- **M3U links and files** need a guide link: either the one you typed when adding the playlist, or one named on the playlist's first line. Without either, there's no guide. Ask your provider for their XMLTV (EPG) link and add it under **Settings › Guide and refresh › Extra guide links**, or edit the playlist in **Settings › Playlists and account › Playlists**.

**3. Is the date on your device right?** Cheap TV boxes often lose the date when unplugged, and then secure downloads fail as if the server were down. Tuned spots this and says so; fix it in Android's settings under Date and time.

## Some channels are empty

The guide matches channels by an ID in the playlist (`tvg-id`). When the provider's guide has no entry for a channel, or the IDs don't match, that channel stays empty.

- **Add an extra guide link** that covers those channels. Extra guides only fill channels the first one left empty, so they never overwrite good data.
- **Fix the channel:** open its guide, tap the **calendar** button, and take its programmes from another entry in the guide. Pick the right one from the list of channels the guide has. The fix stays through refreshes.

## The times are wrong

- **Everything is off by the same amount:** use **Settings › Guide and refresh › Guide time offset** (half-hours or whole hours, up to 12 either way).
- **One channel is off:** use its **calendar** button and move only that channel.
- **Times show in the wrong time zone:** **Settings › Guide and refresh › Time zone**. The phone's own zone is the default.

## It only covers a day or two

**Guide days to keep** in Settings › Guide and refresh sets how far ahead Tuned keeps. It can't keep more than your provider sends.

More on the guide: [setting up the TV guide](/blog/guides/tv-guide-epg/).
