---
title: How to sign in to Tuned on a TV with your phone
description: Skip typing a server and password with a remote. Scan a QR code on the TV and fill it in on your phone instead.
date: 2026-11-19
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/sign-in-tv.jpg" alt="Tuned's Send to a TV on a phone beside the TV's sign-in code and QR code" width="1200" height="760"></figure>

Typing a server address, username and password with a TV remote is slow, and it's easy to get one letter wrong. Tuned lets your phone do the typing.

## On the TV

1. Open Tuned on your Android TV or Google TV.
2. The first card, already selected, is **Sign in with your phone**. Press OK.
3. The TV shows a **QR code** and an eight-character code, like `K7QF-29MD`. The code lasts 10 minutes, then the TV makes a new one by itself.

## On a phone without Tuned

1. Point the phone's camera at the QR code and open the link.
2. Fill in your **Xtream login** or **playlist link**. Pasting a whole `get.php` link fills in the login for you.
3. Check the four digits on the phone match the ones on the TV, and send.

Can't scan? Open the address the TV shows on any phone or computer and type the code instead.

<img src="/blog/img/guides/phone-page.webp" alt="The Sign in on your TV page on a phone, with Xtream login and Playlist link tabs" width="270" height="585" loading="lazy">

<img src="/blog/img/guides/phone-page-check.webp" alt="The phone asks you to check that your TV shows the same four-digit number" width="270" height="585" loading="lazy">

## On a phone with Tuned

If Tuned is already set up on your phone, it can send everything at once: every saved playlist (except playlist files), your settings, profiles with their favourites, lists and watch later, channel renames, guide fixes, and your Trakt and OpenSubtitles sign-ins.

- Scan the TV's QR code with the phone's camera, and it opens in Tuned, or
- go to **Settings › Playlists and account › Playlists › Send to a TV** and type the code from the TV.

<img src="/blog/img/guides/send-to-tv.webp" alt="Send to a TV in Tuned on a phone, asking for the code shown on the TV" width="270" height="586" loading="lazy">

## The TV asks before it saves

The TV shows what arrived (the playlist names and what else came, never a password) and keeps it only when you pick **Use it**. **Not mine** throws it away and shows a new code. So someone reading the code off your screen can't slip a playlist onto your TV.

A new TV takes everything. A TV that's already set up adds only the playlists and keeps its own settings and favourites.

## Only your TV can read it

The TV makes a one-time key and puts it in the QR code. Your phone locks the login with that key before sending it. Tuned's sign-in service only passes the locked box along: it can't open it, and it deletes it as soon as the TV takes it, or after 10 minutes.

## Prefer the remote?

**Type it on the TV instead** takes you back to the usual forms. On a TV, the password shows as you type, so you can check it.

If you use a [Tuned account](/blog/guides/premium/), a TV can also sign in to it with your phone and restore your backup in one step.

Moving your whole setup to a new phone or TV: [move without starting over](/blog/guides/move-iptv-to-a-new-phone-or-tv/).
