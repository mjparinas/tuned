---
title: What Tuned stores, and what it never sees
description: Where your login, playlists and history live, who Tuned talks to and why, and what the optional Tuned account keeps.
date: 2026-11-19
kind: guide
---

Tuned is a player. It has no ads, no third-party analytics and no content of its own, and almost everything it knows stays on your device. Here's the plain-English version.

## On your device

- **Your playlist logins** (server, username and password, playlist link or MAC address) are encrypted with a key kept in Android's Keystore, which never leaves the device. They're deleted when you sign out.
- **Your playlists, guide, favourites, watch history, settings, downloads and recordings** stay on the device.
- **Crash reports and the app's log** stay on the device too, with logins removed before anything is written. They leave only if you share them.
- **Android's cloud backup is off** for Tuned, so none of this ends up in a phone backup.

## What Tuned never sees

- **What you watch.** No history, channel or title is sent to Tuned.
- **Your card details.** Google Play or Stripe handle payment.
- **Your Trakt password.** You sign in on Trakt's own site with a code.

## Who Tuned talks to, and why

Only what's needed to do what you asked:

- **Your IPTV provider**, to sign in, download the playlist and guide, and play streams.
- **The Movie Database (TMDB)**, for posters, ratings, episode names and cast. It gets titles, never your login.
- **OpenSubtitles**, only when you search for subtitles.
- **Trakt**, only if you connect it, to record what you watch to your own Trakt account.
- **YouTube**, only when you open a trailer.
- **GitHub**, in the GitHub version, to check for updates. **Google Play**, in the Play version, for purchases.

## Signing in on a TV with your phone

Your phone locks the login with a one-time key that only your TV holds. Tuned's sign-in service passes the locked box along without being able to open it, and deletes it as soon as the TV takes it, or after 10 minutes. See [sign in on a TV with your phone](/blog/guides/sign-in-on-tv-with-phone/).

## If you make a Tuned account

The account is optional. If you sign in with Google, Tuned's service keeps:

- your **email address and name** from Google,
- your **devices**: the name Android gives them (like "Pixel 8"), the kind of device, a scrambled device ID and when each was last used,
- **what you bought**,
- and, if you use it, **your backup**: saved playlists (including their logins), settings, profiles, favourites, lists and watch later. The last 5 versions are kept.

The backup isn't encrypted by Tuned; only devices signed in to your account can read it. You can delete it, or your whole account, on the account page any time.

## Signing out

**Settings › Playlists and account › Sign out** resets Tuned to how it was installed: every playlist and login, the cache, profiles, settings and history are removed. It asks whether to keep your downloads. Your Premium and your account backup stay, so signing back in can bring everything back.

The full privacy policy has the details.
