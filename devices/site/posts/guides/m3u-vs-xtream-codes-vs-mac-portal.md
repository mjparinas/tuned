---
title: "M3U, Xtream Codes or MAC portal: which should you use?"
seoTitle: "M3U vs Xtream Codes vs MAC portal: which IPTV login is best?"
description: Your provider may offer an M3U link, an Xtream Codes login or a MAC portal. What each one is, what you get with it, and which to pick in your player.
date: 2026-11-27
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/playlist-types.jpg" alt="Three phones showing Tuned's Xtream Codes login, Playlist link and MAC portal forms" width="1200" height="760"></figure>

Many providers let you choose how to sign in: a long M3U link, an Xtream Codes login (server, username and password) or a MAC portal. They can carry the same channels, but they don't give a player the same things. Here's the short answer, then the detail.

**The short answer:** pick **Xtream Codes** if you have the choice. It brings the TV guide, catch-up, films and shows by itself, and it's the easiest to type. Use an **M3U link** when that's all you have, and a **MAC portal** only when your provider offers nothing else.

## The three side by side

|                                    | Xtream Codes                    | M3U link or file                                  | MAC portal                       |
| ---------------------------------- | ------------------------------- | ------------------------------------------------- | -------------------------------- |
| What you type                      | Server, username, password      | One long link (or a file)                         | Portal address and a MAC address |
| TV guide                           | Comes with it                   | Only if the playlist names one, or you add a link | Comes with it                    |
| Catch-up                           | Yes, where the provider records | No                                                | No                               |
| Films and shows                    | Yes, with posters and seasons   | When the playlist includes them                   | Not in Tuned for now             |
| Subscription and connections shown | Yes                             | No                                                | No                               |
| Works on                           | Almost every player             | Every player                                      | MAG boxes and a few players      |

## Xtream Codes

An Xtream Codes login is three things: a **server address** like `http://example.com:8080`, a **username** and a **password**. The player asks the server for everything it needs: live channels, films, shows with their seasons, the guide, and which channels keep catch-up recordings.

In Tuned, that also means **Settings › Playlists and account** can show your subscription's end date and how many of your connections are in use.

<div class="shots">
<figure><img src="/blog/img/guides/playlist-welcome.webp" alt="Tuned's welcome screen with Xtream Codes login, Playlist link, Playlist file, MAC portal and Media server" width="270" height="586" loading="lazy"><figcaption>Tuned's first screen: every kind of playlist.</figcaption></figure>
<figure><img src="/blog/img/guides/playlist-add-xtream.webp" alt="Tuned's Xtream Codes login form with Server, Username, Password and an optional name" width="270" height="586" loading="lazy"><figcaption>Xtream Codes: three boxes, and you're done.</figcaption></figure>
</div>

**Got an M3U link with `get.php?username=…&password=…` in it?** That's an Xtream login in disguise. Paste it into Tuned's Playlist link and Tuned pulls the login out and sets it up as Xtream, catch-up included. Step by step: [adding an Xtream Codes playlist](/blog/guides/add-xtream-codes-playlist/).

## M3U link or file

M3U is the oldest and most universal format: a plain text list of channels, each with a name, a logo, a group and a stream link. Any player can open one, even VLC.

What it doesn't carry is everything around the channels. The guide is a separate XMLTV link: many playlists name theirs on the first line, and Tuned reads it on its own; otherwise paste your provider's guide link. There's no catch-up, and no subscription details. Films and shows only appear if the provider put them in the list, and Tuned then sorts them into Movies and Shows by their links and groups.

<figure><img src="/blog/img/guides/playlist-add-link.webp" alt="Tuned's Playlist link form with a playlist link, an optional guide link and a name" width="270" height="586" loading="lazy"><figcaption>An M3U link, with an optional guide link.</figcaption></figure>

Some M3U links carry a token that runs out, so a playlist that worked last month may suddenly fail. Ask your provider for a fresh link, or for an Xtream login instead. More: [adding an M3U playlist](/blog/guides/add-m3u-playlist/).

## MAC portal (Stalker, Ministra)

A MAC portal was made for MAG set-top boxes. Instead of a password, the provider registers a **MAC address** and gives you a **portal address**, usually ending in `/c/`. Tuned signs in the way a MAG box does.

<figure><img src="/blog/img/guides/playlist-add-portal.webp" alt="Tuned's MAC portal form with the portal address, the MAC address, a name and an optional guide link" width="270" height="586" loading="lazy"><figcaption>The MAC portal form.</figcaption></figure>

You get Live TV and the portal's guide. Films and shows from portals aren't in Tuned for now: portals list them about fourteen at a time, so a big library would take thousands of requests before anything showed. Tuned keeps your MAC like a password: encrypted, hidden in the logs, and forgotten when you sign out. More: [adding a MAC portal](/blog/guides/add-mac-portal/).

## Whichever you pick, the first download is the same

Tuned downloads the list and the guide once and keeps them on your device, showing each step with real progress. After that, Live TV opens instantly and refreshes in the background.

<div class="shots">
<figure><img src="/blog/img/guides/browse-first-download.webp" alt="Tuned getting a playlist ready, with seven numbered steps and a progress bar showing 45 percent of 12,345 items" width="270" height="586" loading="lazy"><figcaption>The first download: numbered steps, real progress.</figcaption></figure>
<figure><img src="/blog/img/guides/browse-live-tv.webp" alt="Tuned's Live TV list on a phone with channel logos and what's on now" width="270" height="586" loading="lazy"><figcaption>Then Live TV, from the device, every time.</figcaption></figure>
</div>

Tuned is a player for your own playlist. It includes no channels: the login or link comes from your provider. New to all of this? Start with [how to set up IPTV on your TV](/blog/guides/set-up-iptv-on-tv/), or look up a term in the [IPTV glossary](/blog/guides/glossary/).
