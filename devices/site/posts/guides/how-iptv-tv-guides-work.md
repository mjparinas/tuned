---
title: "How IPTV TV guides work: XMLTV, channel IDs and time zones, explained"
seoTitle: "How the IPTV EPG works: XMLTV, tvg-id matching, time offsets and fixing a wrong guide"
description: A deep dive into the IPTV TV guide (EPG). What an XMLTV file is, how a player matches it to channels, why times go wrong, and how to fix every common guide problem.
date: 2026-11-27
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/epg-deep-dive.jpg" alt="Tuned's TV guide grid on a TV beside a phone searching the guide for a programme" width="1200" height="760"></figure>

The TV guide is the part of IPTV most people notice only when it's wrong: a blank row, a programme that started an hour ago, a channel showing another channel's schedule. Once you know how a guide is put together, each of those has an obvious cause and a quick fix.

This is the deep version. For the quick how-to, see [setting up the TV guide](/blog/guides/tv-guide-epg/); for a guide that's empty right now, [the TV guide is empty](/blog/guides/blank-tv-guide/).

Tuned is a player for your own playlist and includes no channels or guide data. The guide comes from your provider, or from guide links you add.

## The guide is a separate file

Your channel list and your TV guide are two different downloads. The channels come from the playlist; the guide comes from an **XMLTV** file (often called the EPG link). It's a plain text file listing, for each channel, every programme for the next few days:

```xml
<tv>
  <channel id="lakeshore.tv">
    <display-name>Lakeshore TV</display-name>
  </channel>
  <programme channel="lakeshore.tv" start="20261127190000 +0000" stop="20261127200000 +0000">
    <title>Riverton Hockey Hour</title>
    <category>Sports</category>
  </programme>
</tv>
```

Three things in there decide whether your guide looks right: the **channel ID** (`lakeshore.tv`), the **times** with their offset (`+0000`), and the **category**.

## Where the guide comes from

- **Xtream Codes logins:** the server provides it at a known address, so the player fetches it on its own.
- **MAC portals:** the portal sends it, up to about a week ahead.
- **M3U playlists:** the playlist's first line often names it (`#EXTM3U url-tvg="…"`). If it doesn't, you add the link your provider gave you.
- **Plex, Jellyfin and Emby:** the server's own Live TV guide.

## Matching: how a programme finds its channel

Each channel in an M3U playlist carries a `tvg-id`. Each programme in the guide carries a `channel` ID. When the two are identical, the programme appears under that channel. That's the whole trick, and it's where most guide problems come from:

- The playlist says `lakeshoretv.ca`, the guide says `lakeshore.tv`: **no match, blank row**.
- Two channels share one ID: **both show the same schedule**.
- The provider renamed a channel's ID in the guide but not the playlist: **a row that worked yesterday is blank today**.

A player can't safely guess its way around a mismatch (guessing by name would put the wrong schedule on half your channels), so the fix is to tell it which guide entry belongs to which channel.

### Fixing one channel

Open the channel's guide in Tuned and tap the **calendar** button. Pick the right entry from the list of every channel the guide contains, and that channel takes its programmes from it. The fix is kept through every refresh, and every screen that shows the guide (the list, the grid, the player, search, Live events) uses it.

<figure><img src="/blog/img/guides/browse-guide.webp" alt="A channel's guide in Tuned with what's on now and the rest of the day, each upcoming programme with a reminder bell and a record button" width="270" height="586" loading="lazy"><figcaption>A channel's guide, where the calendar button fixes it.</figcaption></figure>

### Filling gaps with extra guides

When your provider's guide simply doesn't cover some channels, add another XMLTV link in **Settings › Guide and refresh › Extra guide links**. Tuned reads extra guides after your provider's, and they only fill channels that are still empty, so they never overwrite good data. They're stored encrypted, because guide links often carry a key.

## Time: why a guide is an hour off

Every programme's time carries an offset from UTC: `+0000` means UTC, `-0500` means five hours behind it. A correct guide plus a correct device shows the right local time. Things go wrong when:

- **The guide writes local times but labels them `+0000`.** Everything is off by your distance from UTC.
- **The guide ignores daylight saving.** Everything shifts by an hour twice a year.
- **One channel's feed comes from another time zone**, so only that channel is off.
- **The device's own clock or time zone is wrong.** Cheap boxes often lose the date when unplugged.

The fixes, from widest to narrowest:

| Problem                          | Fix in Tuned                                                                                    |
| -------------------------------- | ----------------------------------------------------------------------------------------------- |
| Every channel off by the same    | **Settings › Guide and refresh › Guide time offset** (half-hours or hours, up to 12 either way) |
| Guide times in the wrong zone    | **Settings › Guide and refresh › Time zone** (your phone's by default)                          |
| One channel off                  | The channel's **calendar** button: move just its times                                          |
| A playlist line says `tvg-shift` | Nothing: Tuned applies it to that channel by itself                                             |
| Device date wrong                | Android's Date and time settings; Tuned warns you when it looks wrong                           |

<figure><img src="/blog/img/guides/settings-page-guide.webp" alt="Tuned's Guide and refresh settings with how often to refresh, guide days, time offset, extra guide links and time zone" width="270" height="586" loading="lazy"><figcaption>Settings › Guide and refresh: offset, time zone, extra guides.</figcaption></figure>

## Days ahead, and how fresh it is

A guide covers as many days as your provider sends, often 2 to 7. **Guide days** in Settings sets how many Tuned keeps, which can't be more than the provider sends. Tuned refreshes the guide in the background every 6 hours by default (the channel list every 12), saving it into a database on the device, so opening the app never waits on a guide download. Big guides are tens of megabytes of text; Tuned shows the real size and each step as it goes.

## Categories: what the guide knows about a programme

The `<category>` tag says what a programme is: Sports, Movie, News, Kids, Documentary. Tuned reads it when the guide is saved and uses it in two places:

- **Filters › What's on now** on Live TV shows only channels with sport (or films, news, kids, documentaries) on right now.
- **Live events** gathers today's games and fights from the guide into one page. When a guide has no category, a programme title with two sides ("Harbour Hawks vs Lakeside Lions") still counts as sport.

<div class="shots">
<figure><img src="/blog/img/guides/browse-live-tv-filters.webp" alt="Tuned's Live TV filters on a phone: type, quality, what's on now and country" width="270" height="586" loading="lazy"><figcaption>"What's on now" filters come from the guide.</figcaption></figure>
<figure><img src="/blog/img/guides/browse-search-guide.webp" alt="Searching in Tuned finds channels, then every programme in the guide with that word in its name" width="270" height="586" loading="lazy"><figcaption>Search reads every programme in the guide.</figcaption></figure>
</div>

## What a good guide gives you

Once the guide is right, it powers much more than "what's on":

- **The grid**, channels down the side and time across the top. Tuned reads the guide only for the rows on screen, so it stays quick with thousands of channels.
- **Search** that finds a programme, not just a channel name.
- **Reminders**: a bell beside any programme, and a notification a few minutes before.
- **Recording** from the guide, with a margin either side (Premium).
- **Catch-up**: on Xtream servers that record, past programmes in the guide play like films.
- **Your guide in a browser**: the same guide on [tunedplayer.com](/blog/guides/channels-and-guide-on-your-computer/), with your account.

<figure><img src="/blog/img/guides/browse-guide-grid-tv.webp" alt="Tuned's TV guide grid on a TV, with channels down the side, half-hour times across the top and a line at the current time" width="640" height="360" loading="lazy"><figcaption>The grid on a TV, with a line at the current time.</figcaption></figure>

<figure><img src="/blog/img/guides/web-guide.webp" alt="The Channels and guide page on tunedplayer.com, with a grid guide of made-up channels in a browser" width="720" height="500" loading="lazy"><figcaption>The same guide in a browser.</figcaption></figure>

## A guide troubleshooting order

1. Has the first guide download finished? (Settings › Guide and refresh shows the last refresh and any error.)
2. Does your playlist have a guide at all? (Xtream and MAC: yes. M3U: only if named or added.)
3. Is the device's date right?
4. Is everything off by the same amount? Use the guide offset.
5. Is one channel blank or wrong? Use its calendar button.
6. Are some channels missing entirely? Add an extra guide link.

Everything else about IPTV, end to end: [how IPTV works](/blog/guides/how-iptv-works/).
