---
title: "IPTV glossary: EPG, M3U, Xtream Codes, catch-up and more"
description: Plain-English meanings of the IPTV terms you'll meet when setting up a player, from EPG and M3U to MAC portals and catch-up.
date: 2026-11-19
kind: guide
---

IPTV set-up comes with a lot of jargon. Here's what each term means, and where it shows up in Tuned.

## Playlists and logins

**IPTV.** Internet Protocol Television: TV channels, films and shows delivered over the internet instead of cable, satellite or an aerial. Tuned is a player; the channels come from your provider.

**Playlist.** The list of channels, films and shows your provider gives you, with a link to each stream.

**M3U / M3U8.** The most common playlist format: a text file starting with `#EXTM3U`, with one entry per channel giving its name, logo, group and stream link. M3U8 is the same thing saved as UTF-8. See [adding an M3U playlist](/blog/guides/add-m3u-playlist/).

**Xtream Codes.** A kind of IPTV server many providers use. You sign in with a server address, username and password, and get live TV, films, shows, the guide and catch-up. See [adding an Xtream Codes playlist](/blog/guides/add-xtream-codes-playlist/).

**MAC portal (Stalker, Ministra).** A server made for MAG set-top boxes. Instead of a password, your provider registers your device's MAC address. See [adding a MAC portal](/blog/guides/add-mac-portal/).

**Plex, Jellyfin and Emby.** Home media servers. Tuned can add one as a playlist for Live TV, films and shows from your own library. See [adding a media server](/blog/guides/add-media-server/).

**MAC address.** A hardware ID written as six pairs of characters, like `00:1A:79:12:34:56`. For a MAC portal, it's the one your provider registered.

**Connections.** How many streams your account may play at once. Tuned always uses one. See [connections explained](/blog/guides/iptv-connections-explained/).

## The guide

**EPG.** Electronic Programme Guide: what's on now, next and later on each channel. See [setting up the TV guide](/blog/guides/tv-guide-epg/).

**XMLTV.** The file format most EPGs come in. A "guide link" or "EPG link" is an XMLTV link.

**tvg-id.** The ID in an M3U playlist that ties a channel to its entry in the guide. When it doesn't match, the channel's guide is empty; see [the TV guide is empty](/blog/guides/blank-tv-guide/).

## Watching

**Live TV.** Channels as they air.

**VOD.** Video on demand: films and shows you start whenever you like.

**Catch-up (TV archive).** Your provider's recordings of past programmes, usually a few days back. See [catch-up](/blog/guides/catch-up/).

**Timeshift, or pause and rewind live TV.** Pausing a live channel and going back a few minutes. In Tuned, the device keeps the last 30 minutes of the channel you're watching.

**Buffering.** The player waiting for more of the stream to arrive. See [fixing buffering](/blog/guides/buffering/).

**HLS.** HTTP Live Streaming, a common way of sending video in short pieces; its playlists end in `.m3u8`.

**TS (MPEG-TS).** The format most live IPTV channels are sent in: one endless stream.

**User agent.** The name an app gives a server when it asks for something. Some servers only answer players they know. See [changing the user agent](/blog/guides/user-agent/).

## Devices

**Android TV / Google TV.** Android for TVs. Google TV is Android TV with Google's home screen. See [Tuned on Android TV](/blog/guides/android-tv/).

**Chromecast.** Google's way of sending video from a phone to a TV. See [casting](/blog/guides/casting/).

**DLNA / UPnP.** An older standard many smart TVs use to play video from other devices on the same network. Tuned casts to these too.

**Picture-in-picture.** Android shrinking the video you're watching into a small window when you leave the app.

**Frame rate matching.** Switching the TV's refresh rate to the video's (24, 25, 50 or 60 frames a second) so motion looks smooth.
