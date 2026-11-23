---
title: How to add Plex, Jellyfin or Emby to Tuned
seoTitle: "Add Plex, Jellyfin or Emby as a Tuned playlist"
description: Connect a home media server to Tuned for Live TV, films and shows, with the server's own guide when it has one.
date: 2026-11-22
kind: guide
---

Tuned isn't only for IPTV providers. You can add a **Plex**, **Jellyfin** or **Emby** server the same way you add a playlist, and get Live TV, films and shows from your own library on your phone, tablet and Android TV.

Tuned includes no channels or content of its own. You need a server you already run, or an Xtream, M3U or MAC playlist from your provider.

## Before you start

- Know your server's address, such as `http://192.168.1.20:8096` for Jellyfin or Emby, or your Plex URL.
- **Jellyfin and Emby:** a username and password on that server.
- **Plex:** an [X-Plex-Token](https://www.plex.tv/account/) from plex.tv/account. Tuned keeps it encrypted like any other login.

## Add the server

1. Open Tuned. On the first screen, pick **Media server**, then **Plex**, **Jellyfin** or **Emby**.
2. Type the **Server** address. For Jellyfin or Emby, add the **Username** and **Password**. For Plex, paste the token into the password field.
3. Give it a **Name** if you like. It's optional.
4. Tap **Add playlist**.

Tuned checks the login first, then opens. Live TV channels come from the server's Live TV API; films and shows follow as a library step. Stream links stay as `plex://`, `jellyfin://` or `emby://` on the device and are resolved when you play, so the token never sits in a plain playlist file.

## What you get

- **Live TV, Movies and Shows** from that server, with posters and details when Tuned can match them.
- **The server's own guide** when it has one and you didn't add a separate XMLTV link.
- **The same player** as for IPTV: captions, cast, mini player, one stream at a time.

A browser on tunedplayer.com can't reach a home server on your LAN, so the website's provider check stays "app only" for these. Playlists and settings for them still sync through your Tuned account.

## On a TV

Prefer **Sign in with your phone** and fill the form there, rather than typing a long token with the remote. See [sign in on a TV with your phone](/blog/guides/sign-in-on-tv-with-phone/).

## Next

- [Add an Xtream Codes playlist](/blog/guides/add-xtream-codes-playlist/) if you also have an IPTV login.
- [Tuned on Android TV](/blog/guides/android-tv/) for remote tips and calibrate screen.
- [Premium, seats and devices](/blog/guides/premium/) when you want several playlists on one account.
