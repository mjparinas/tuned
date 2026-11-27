---
title: Switching from TiviMate or IPTV Smarters to Tuned in 5 minutes
seoTitle: "Switch from TiviMate or IPTV Smarters: move your IPTV playlist in 5 minutes"
description: Moving to a new IPTV player is quicker than you think. Bring the same login or link, your guide and your favourite channels, and set Tuned up the way you like it.
date: 2026-11-26
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/switch-player.jpg" alt="Three phones showing Tuned's welcome screen, the Xtream Codes login form and Live TV's filters" width="1200" height="760"></figure>

Switching IPTV players feels like a big job, but your channels don't live in the player. They live with your provider. The login or link you typed into TiviMate, IPTV Smarters, OTT Navigator or any other player works in Tuned too, and your provider sees the same account either way.

Tuned is a player for your own playlist and includes no channels. Nothing about your provider changes when you switch.

## Before you start: find your login

Look in your old player's playlist settings, or the email your provider sent:

- **Xtream Codes**: a server address (like `http://example.com:8080`), a username and a password. In IPTV Smarters this is "Login with Xtream Codes API". In TiviMate it's the Xtream Codes playlist type.
- **M3U link**: a long link ending in `.m3u`, `.m3u8`, or containing `get.php?username=…`.
- **MAC portal**: a portal address, plus the MAC address the provider registered.

If you can, choose **Xtream Codes** over an M3U link. It brings the guide, catch-up, films and series automatically. If all you have is a `get.php` link, Tuned spots the login inside it and sets it up as Xtream for you.

## Step 1: Add the playlist (1 minute)

Install Tuned, open it, and pick the same kind of playlist you used before.

<div class="shots">
<figure><img src="/blog/img/guides/playlist-welcome.webp" alt="Tuned's welcome screen on a phone with Xtream Codes login, Playlist link, Playlist file, MAC portal and Media server" width="270" height="586" loading="lazy"><figcaption>Pick the kind of playlist you have.</figcaption></figure>
<figure><img src="/blog/img/guides/playlist-add-xtream.webp" alt="Tuned's Xtream Codes login form with Server, Username, Password and an optional name" width="270" height="586" loading="lazy"><figcaption>Paste the same server, username and password.</figcaption></figure>
</div>

On a TV, choose **Sign in with your phone** and do the typing on your phone instead of the remote. See [sign in on a TV with your phone](/blog/guides/sign-in-on-tv-with-phone/).

**Does it use a second connection?** Not while you set up. Tuned downloads the list and guide with ordinary requests, not a stream. Just don't play in both apps at once if your plan allows one connection.

## Step 2: Check the guide (1 minute)

Xtream Codes and MAC portals bring their guide on their own. For an M3U link, paste the same guide (EPG, XMLTV) link you used in your old player, or let Tuned read the one the playlist names on its first line.

If you added extra guide links in your old player to fill gaps, add them in **Settings › Guide and refresh › Extra guide links**. Tuned reads them after your provider's guide and only fills channels it left empty.

<figure><img src="/blog/img/guides/settings-page-guide.webp" alt="Tuned's Guide and refresh settings with how often to refresh, guide days, time offset, extra guide links and time zone" width="270" height="586" loading="lazy"><figcaption>Settings › Guide and refresh.</figcaption></figure>

A channel whose guide is wrong or an hour off can be fixed on its own: open its guide and tap the calendar button. The fix stays through every refresh. More in [setting up the TV guide](/blog/guides/tv-guide-epg/).

## Step 3: Hide what you never watch (2 minutes)

Big playlists come with channels from dozens of countries. Instead of scrolling past them, hide them:

- **Settings › Channels and lists** hides whole countries, languages and groups.
- **Filters** on Live TV narrow the list to sports, news, kids, 4K or a country, and stay chosen until you change them.

<div class="shots">
<figure><img src="/blog/img/guides/settings-countries.webp" alt="Tuned's country settings listing the countries found in the playlist, each with a switch" width="270" height="586" loading="lazy"><figcaption>Hide countries you don't watch.</figcaption></figure>
<figure><img src="/blog/img/guides/browse-live-tv-filters.webp" alt="Tuned's Live TV filters on a phone: type, quality, what's on now and country" width="270" height="586" loading="lazy"><figcaption>Filters on Live TV stay chosen.</figcaption></figure>
</div>

## Step 4: Bring your favourites back (1 minute)

Players don't share favourites with each other, so tap the heart on the channels you watch most. They get their own chip at the top of Live TV and, on Android TV and Google TV, their own row on the home screen. With [Premium](/blog/guides/premium/) you can make your own lists beside Favourites ("Sport", "Kids"), rename channels and change their logos.

<figure><img src="/blog/img/guides/settings-page-channels.webp" alt="Tuned's Channels and lists settings with Start on the last channel, channel numbers, guide grid and Order favourites" width="270" height="586" loading="lazy"><figcaption>Channel numbers, start on the last channel and the order of your favourites.</figcaption></figure>

## Things that work the way you're used to

- **Channel numbers** and the **last-channel button** on the remote.
- **Up and down change channel** while you watch, like a cable box.
- **A grid guide**, with a reminder on anything coming up.
- **Catch-up** for providers that record, from the channel's guide.
- **Several playlists**, switched with a tap (Premium).

## What's different in Tuned

- **It opens on your channels at once.** The playlist is kept on the device and refreshed in the background, so there's no loading screen each time.
- **One stream connection, always.** No live previews, no background downloads while you watch, so you stay inside your provider's limit.
- **No menus to dig through.** Every action is a button the remote can reach.
- **Free with no ads.** The free app covers one playlist and its guide, the whole player and casting. [Premium](/blog/guides/premium/) is US$9.99 a year or US$24.99 once.

Side-by-side tables: [Tuned vs TiviMate](/blog/vs/tivimate/), [Tuned vs IPTV Smarters Pro](/blog/vs/iptv-smarters-pro/), [Tuned vs OTT Navigator](/blog/vs/ott-navigator/).

## Moving more than one device?

Set up one device, then send everything to the others. On a phone, **Settings › Playlists and account › Playlists › Send to a TV** passes every playlist, your settings and favourites to a TV in one go. With a [Tuned account](/blog/guides/premium/), every device you sign in to restores the same playlists, profiles and settings.

Five minutes, and you're watching the same channels in a player that gets out of your way.
