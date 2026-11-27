---
title: How to add an Xtream Codes playlist to Tuned
description: Sign in with the server address, username and password your provider sent, and get Live TV, Movies, Shows and the guide in one step.
date: 2026-11-19
kind: guide
updated: 2026-11-26
---

Most IPTV providers send three things when you sign up: a **server address**, a **username** and a **password**. That's an Xtream Codes login, and it's the most complete way to use Tuned: it brings in live channels, films, shows, the TV guide and catch-up from one sign-in.

Tuned includes no channels or content of its own. You need a playlist from your own provider.

## Before you start

- Find the email or message from your provider. The server usually looks like `http://example.com:8080`.
- If your provider sent a long link with `get.php` or `player_api.php` in it, that works too. It already holds the server, username and password.

## Add the playlist

1. Open Tuned. On the first screen, pick **Xtream Codes login**.
2. Type the **Server**, **Username** and **Password**. Tap the eye in the password box to check what you typed.
3. Give it a **Name** if you like, such as "Living room" or "Sports". It's optional.
4. Tap **Add playlist**.

<img src="/blog/img/guides/welcome.webp" alt="Tuned's welcome screen with ways to add a playlist: Xtream Codes login, Playlist link, Playlist file, MAC portal and Media server" width="270" height="586" loading="lazy">

Tuned checks the login with your server first, which takes a second, then opens straight away. The first download of channels and the guide runs with a progress bar, and the lists fill in as it arrives, so you can start browsing before it finishes.

<img src="/blog/img/guides/xtream-form.webp" alt="The Xtream Codes login form with Server, Username, Password and an optional Name" width="270" height="586" loading="lazy">

## Pasting a whole link

Got a link like `http://example.com:8080/get.php?username=…&password=…`? Paste it into the **Server** box and Tuned fills in all three fields for you. Pasting the same link into **Playlist link** works as well: Tuned spots that it's an Xtream login and treats it like one, so catch-up and your account status still work.

## What you get

- **Live TV, Movies and Shows**, sorted by your provider's groups and by country.
- **The TV guide**, downloaded with the playlist. See [setting up the TV guide](/blog/guides/tv-guide-epg/).
- **Catch-up** on channels your provider records. See [catch-up](/blog/guides/catch-up/).
- **A cache on your device.** Next time you open Tuned, the channels show at once, before anything downloads. Tuned refreshes in the background: channels every 12 hours and the guide every 6, both changeable in Settings › Guide and refresh.

If your provider has turned off the playlist download, Tuned reads the same channels, films and shows through the server's other interface instead, without you doing anything.

<div class="shots">
<figure><img src="/blog/img/guides/browse-live-tv.webp" alt="Tuned's Live TV after adding an Xtream Codes login, with channel logos and what's on now" width="270" height="586" loading="lazy"><figcaption>Live TV, with the guide filled in.</figcaption></figure>
<figure><img src="/blog/img/guides/browse-shows.webp" alt="Tuned's Shows tab with posters from the provider's series" width="270" height="586" loading="lazy"><figcaption>Series get their own tab with posters.</figcaption></figure>
</div>

## Your login stays on your device

The password is stored encrypted with a key that never leaves your phone or TV, and it's forgotten when you sign out. Tuned never puts it in its logs. More in [what Tuned stores](/blog/guides/privacy/).

## If it doesn't work

- **"The server didn't accept that username and password."** Check for a space at the end, and capital letters. Copy and paste them if you can.
- **Nothing loads.** Check that the server starts with `http://` and includes the port (the number after the colon).
- More fixes in [provider errors](/blog/guides/provider-errors/).

## Adding a second playlist

Go to **Settings › Playlists and account › Playlists › Add a playlist**. Tuned keeps every login you've added and switches between them with a tap. More than one playlist is part of [Premium](/blog/guides/premium/) in the Google Play version.

Setting up a TV? Typing a password with a remote is slow, so [sign in with your phone](/blog/guides/sign-in-on-tv-with-phone/) instead.
