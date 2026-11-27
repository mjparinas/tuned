---
title: "Plex, Jellyfin or Emby plus IPTV in one app"
seoTitle: "Plex, Jellyfin or Emby and IPTV in one app on Android TV and phones"
description: Watch your own Plex, Jellyfin or Emby library and your IPTV provider's live channels in the same app, with one guide, one player and a tap to switch. Set-up, tips and limits.
date: 2026-11-27
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/media-server-iptv.jpg" alt="Tuned's Jellyfin sign-in form beside its Shows page with Continue watching and posters" width="1200" height="760"></figure>

If you run a home media server, you probably also juggle apps: Plex or Jellyfin for your own films and recordings, an IPTV player for live channels, and a remote that's always on the wrong one. Tuned plays both. Add your server the same way you'd add an IPTV playlist, and switch between them with a tap.

Tuned includes no content of its own. Your server holds your library; your IPTV provider, if you have one, holds the channels.

## Why put them in one app

- **One player** with the same controls, captions, subtitle search, casting and remote shortcuts for everything.
- **One Live TV**: if your server records from a TV tuner, its Live TV and guide show up the same way an IPTV provider's do.
- **Real names over numbers**: posters, ratings and real episode names for your library, as for IPTV films and shows.
- **One connection at a time.** Tuned never plays two things at once, whichever source they come from.

## Plex, Jellyfin and Emby: what you need

- **The server's address**, like `http://192.168.1.20:8096` for Jellyfin or Emby, or your Plex server's address.
- **Jellyfin or Emby**: a username and password on that server. A separate user for the living room TV is a good idea.
- **Plex**: an X-Plex-Token from your Plex account, pasted where the password goes. Tuned keeps it encrypted, like any login.

## Add your server

1. On Tuned's first screen, or in **Settings › Playlists and account › Playlists › Add a playlist**, choose **Media server**.
2. Pick **Plex**, **Jellyfin** or **Emby**.
3. Type the server address, and the username and password (or the Plex token).
4. Give it a name, like "Home server", and tap **Add playlist**.

<div class="shots">
<figure><img src="/blog/img/guides/playlist-welcome.webp" alt="Tuned's welcome screen with Media server among Xtream Codes login, Playlist link, Playlist file and MAC portal" width="270" height="586" loading="lazy"><figcaption>Media server sits beside the IPTV logins.</figcaption></figure>
<figure><img src="/blog/img/guides/playlist-add-jellyfin.webp" alt="Adding a Jellyfin media server in Tuned, with the server address, username, password, a name and an optional guide link" width="270" height="586" loading="lazy"><figcaption>A Jellyfin server: address, username, password.</figcaption></figure>
</div>

Tuned checks the login, then loads Live TV from the server's Live TV (if it has any) and your films and shows from its libraries. Streams are fetched from the server only when you press play, so your token never sits in a plain playlist file.

On a TV, use **Sign in with your phone** and do the typing there: a Plex token is not something you want to enter with a remote. See [sign in on a TV with your phone](/blog/guides/sign-in-on-tv-with-phone/).

## Your library, looking its best

Films and shows from your server appear in Movies and Shows with their posters, ratings, cast and real episode names. Sort films by newest, year, rating or A to Z, and pick up where you left off from Continue Watching.

<div class="shots">
<figure><img src="/blog/img/guides/browse-shows.webp" alt="Tuned's Shows page with Continue watching and posters with ratings" width="270" height="586" loading="lazy"><figcaption>Shows, with Continue watching.</figcaption></figure>
<figure><img src="/blog/img/guides/browse-movies-sort.webp" alt="Tuned's Movies page with the sort menu open: playlist order, recently added, newest, top rated and A to Z" width="270" height="586" loading="lazy"><figcaption>Sort films by newest, rating or A to Z.</figcaption></figure>
</div>

<figure><img src="/blog/img/guides/browse-show.webp" alt="A show's page in Tuned with its seasons and real episode names" width="270" height="586" loading="lazy"><figcaption>Every episode by its real name.</figcaption></figure>

## Adding your IPTV provider beside it

Add your Xtream Codes login, M3U link or MAC portal as a second playlist, the same way. Then switch between your server and your provider from **Settings › Playlists and account › Playlists**. With **Instant playlist switching** (in **Settings › Guide and refresh**, on by default), Tuned keeps a copy of each, so switching back is immediate rather than a fresh download.

More than one playlist is part of [Premium](/blog/guides/premium/): US$9.99 a year with a 7-day free trial, or US$24.99 once, for up to three devices. With one playlist, the free app plays either your server or your provider.

## The guide

If your server has Live TV with a guide, Tuned uses the server's own guide. You can add an XMLTV link instead when you add the server, or extra guide links later in **Settings › Guide and refresh**. Everything about guides: [how TV guides work](/blog/guides/how-iptv-tv-guides-work/).

## Watching away from home

A server at home has a home address, like `192.168.1.20`, that only works on your own Wi-Fi. To watch from elsewhere, use the address your server's own remote access gives you (Plex, Jellyfin and Emby each have a way to reach your server from outside).

## Casting your library

Anything from your server casts to a Chromecast or a DLNA TV like any other film. Your phone becomes the remote. See [casting](/blog/guides/casting/).

## Tips

- **Use a separate server user for Tuned** with only the libraries you want on the TV, especially in a house with kids. Then add [profiles](/blog/guides/parental-controls-iptv/) on top.
- **Name the playlist clearly** ("Home server", "Provider") so switching is obvious.
- **Keep the server's address fixed.** If your router gives the server a new address after a restart, Tuned can't find it. Most routers can reserve an address for a device.

Step-by-step basics are also in [adding a Plex, Jellyfin or Emby server](/blog/guides/add-media-server/).
