---
title: "How IPTV works, from your provider to your screen"
seoTitle: "How does IPTV work? Playlists, streams, the TV guide and the player, explained"
description: A plain guide to what happens between your IPTV provider and your TV: the playlist, the login, streams, the TV guide, connections, buffering and what the player does.
date: 2026-11-27
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/how-iptv-works.jpg" alt="A diagram: your provider sends channels, a login and a guide; your device keeps the list and guide; your screen plays one stream" width="1200" height="760"></figure>

IPTV can feel like a black box: you type a login into an app and channels appear. Knowing what's actually happening makes almost every problem easier to fix, and every choice (which login, which player, which box) easier to make. This guide follows a channel from your provider to your screen.

Tuned is a player. It includes no channels; everything below starts with a playlist from your own provider.

## The two halves: provider and player

Every IPTV setup has two separate parts:

- **The provider** runs the servers. It holds the channels, the films and shows, the TV guide, and your account with its login and limits.
- **The player** is the app on your phone or TV. It asks the provider's servers for things and shows them to you.

They're made by different people. The same login works in any player that understands it, and the same player works with any provider. When something goes wrong, the first question is always which half it's in. More on that split in [is IPTV legal?](/blog/guides/is-iptv-legal/)

## Step 1: the login

Your provider gives you one of three ways in:

- **Xtream Codes**: a server address, a username and a password. The player can ask the server for everything by name: live channels, films, shows, the guide, which channels keep catch-up, and your account's status.
- **An M3U link or file**: one long list of channels, each with a name, a logo, a group and a stream link.
- **A MAC portal**: a server made for MAG boxes, which recognizes a registered MAC address instead of a password.

Which to pick, in detail: [M3U, Xtream Codes or MAC portal](/blog/guides/m3u-vs-xtream-codes-vs-mac-portal/).

<div class="shots">
<figure><img src="/blog/img/guides/playlist-welcome.webp" alt="Tuned's welcome screen with Xtream Codes login, Playlist link, Playlist file, MAC portal and Media server" width="270" height="586" loading="lazy"><figcaption>The ways in, on Tuned's first screen.</figcaption></figure>
<figure><img src="/blog/img/guides/playlist-add-xtream.webp" alt="Tuned's Xtream Codes login form with Server, Username, Password and an optional name" width="270" height="586" loading="lazy"><figcaption>An Xtream login: server, username, password.</figcaption></figure>
</div>

## Step 2: the playlist

With the login, the player downloads the **playlist**: the full list of what your account includes. A big one holds tens of thousands of entries: live channels in groups like "UK | News" or "Sports", plus films and every episode of every show.

Some players download this every time they open, which is why they start with a loading screen. Tuned downloads it once, saves it in a small database on your device, and refreshes it in the background every few hours. That's why it opens straight onto your channels, and why it still opens when the provider's server is having a bad day.

The first download shows each step with real progress, so a big playlist is never a mystery.

<figure><img src="/blog/img/guides/browse-first-download.webp" alt="Tuned getting a playlist ready, with seven numbered steps and a progress bar showing 45 percent of 12,345 items" width="270" height="586" loading="lazy"><figcaption>Downloading, processing and saving: every step, numbered.</figcaption></figure>

## Step 3: sorting live TV, films and shows

A playlist doesn't always say clearly which entry is a live channel and which is a film. Xtream servers mark it in each stream's link; M3U lists often don't. The player has to work it out from the links, the file types and the group names, in several languages. Get it wrong and a "24/7 cartoons" channel turns up under films, or a film collection under live TV. Tuned sorts every entry into Live TV, Movies and Shows as it saves them, and works out each channel's country too, so you can hide the ones you never watch.

## Step 4: the TV guide

The guide is a **separate file**, in a format called XMLTV, listing what's on each channel for the next few days. The player matches each programme to a channel using an ID the playlist gives each channel. When the IDs match, you see "what's on now" under every channel; when they don't, that channel's guide is blank.

Guides are where most "it's broken" moments actually live: the wrong time zone, a channel an hour off, an ID that doesn't match. The full story: [how TV guides work](/blog/guides/how-iptv-tv-guides-work/).

<figure><img src="/blog/img/guides/browse-guide-grid-tv.webp" alt="Tuned's TV guide grid on a TV, with channels down the side and half-hour times across the top" width="640" height="360" loading="lazy"><figcaption>The guide, matched to the channels, as a grid.</figcaption></figure>

## Step 5: pressing play

When you pick a channel, the player asks the provider's server for that one stream. Live channels usually come in one of two shapes:

- **MPEG-TS** (`.ts`): one endless stream of video, like a cable feed down the internet.
- **HLS** (`.m3u8`): the video cut into small pieces a few seconds long, fetched one after another.

The player fills a small **buffer**, a few seconds of video held in reserve, then starts the picture. As long as the stream arrives at least as fast as it plays, the buffer stays topped up. When it doesn't, the buffer runs dry and you see buffering. A bigger buffer takes a moment longer to start but rides out short dips; that's the choice behind Tuned's buffering modes. More in [fixing buffering](/blog/guides/buffering/).

## Step 6: decoding the picture and sound

The stream is compressed. The device's video chip decodes it (usually H.264 or the more efficient HEVC), and the sound is decoded too. Sound is the common trap: many sports channels carry Dolby or DTS sound that some phones can't decode, which gives a silent picture. Tuned picks another sound track when there is one, and has its own Dolby and DTS decoder for devices that lack one. Picture and sound settings in depth: [the best picture and sound on Android TV](/blog/guides/best-picture-and-sound-android-tv/).

## Connections: the limit that matters

Your plan allows a set number of **connections**: streams playing at the same time. One connection means one thing playing, anywhere. Anything that opens a second stream counts, including live previews and background downloads. Tuned never uses more than one: see [connections explained](/blog/guides/iptv-connections-explained/).

## Films, shows and catch-up

Films and episodes are the same idea as live channels, but each is a file with a beginning and an end, so you can pause, skip and resume. Tuned adds posters, ratings and real episode names from The Movie Database by matching titles, and can save them for offline viewing.

**Catch-up** is the provider recording its own channels for a few days. With an Xtream login, the player can ask for a past programme by channel and time, and plays it like a film.

## Where things usually go wrong

| Symptom              | Usually the…           | First thing to try                                       |
| -------------------- | ---------------------- | -------------------------------------------------------- |
| Login refused        | Provider               | Check the subscription and every character of the login  |
| Every channel fails  | Provider or connection | Another device using your connection; restart the router |
| One channel fails    | Provider               | Another copy of the channel                              |
| Buffering            | Internet or server     | A cable to the router; Smooth buffering                  |
| Guide blank or wrong | Guide file             | A guide link; the channel's own fix                      |
| No sound             | Device decoder         | Another sound track                                      |

The full checklist: [IPTV not working? A 10-step fix list](/blog/guides/iptv-not-working/).

Now that you know the moving parts, setting it all up takes about ten minutes: [how to set up IPTV on your TV](/blog/guides/set-up-iptv-on-tv/).
