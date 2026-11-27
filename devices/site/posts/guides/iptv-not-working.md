---
title: "IPTV not working? A 10-step fix list"
seoTitle: "IPTV not working? 10 fixes for no channels, buffering and streams that won't play"
description: Channels won't load, a stream won't start, the guide is empty or everything buffers. Ten checks, in order, that fix most IPTV problems on Android phones and TVs.
date: 2026-11-27
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/iptv-not-working.jpg" alt="Tuned getting a playlist ready with numbered steps, beside its user agent setting" width="1200" height="760"></figure>

When IPTV stops working, it's tempting to reinstall everything. Usually it's one of a handful of things, and most of them aren't the app. Work down this list in order: the first checks are the quickest and fix the most.

The examples use Tuned, but the first five apply to any player.

## 1. Is it one channel, or every channel?

Try three or four channels from different groups.

- **One channel fails, others play:** that channel is down at your provider. Try it later, or another copy of it (step 7).
- **Everything fails:** keep going down this list.

## 2. Is your subscription still active?

The most common cause of "it suddenly stopped" is a subscription that ended. With an Xtream Codes login, Tuned shows the end date and your connections in use under **Settings › Playlists and account**. With an M3U link, ask your provider.

## 3. Is another device using your connection?

Most plans allow one or two streams at once. If someone else in the house is watching on the same login, or an old box was left on a channel, the next stream is refused. With an Xtream login, Tuned warns you before it starts a stream when all your connections are already in use. Turn the other device off and try again. More in [how many devices can I use IPTV on?](/blog/guides/iptv-connections-explained/)

## 4. Restart the router and the TV box

Not a cliché: a box that's been on for weeks, or a router that changed its address, can stop reaching a server. Unplug both for 30 seconds. On a TV, a cable to the router is steadier than Wi-Fi.

## 5. Check the date and time

A device with the wrong date can't make secure connections, so everything looks broken. Tuned spots this and tells you. Fix it in Android's settings under Date and time, and turn on automatic time.

## 6. Re-check the login, character by character

If the playlist won't load at all, a typo is likely: a space at the end, a capital letter, `http://` or the port missing from the server. On a TV, typing with the remote is where most of these come from, so [sign in with your phone](/blog/guides/sign-in-on-tv-with-phone/) instead. The first download shows each step, so you can see exactly where it stopped.

<div class="shots">
<figure><img src="/blog/img/guides/browse-first-download.webp" alt="Tuned getting a playlist ready, with seven numbered steps and a progress bar" width="270" height="586" loading="lazy"><figcaption>Each step of the download, numbered, with real progress.</figcaption></figure>
<figure><img src="/blog/img/guides/playlist-add-xtream.webp" alt="Tuned's Xtream Codes login form with Server, Username, Password and an optional name" width="270" height="586" loading="lazy"><figcaption>Server with http:// and the port, then the username and password.</figcaption></figure>
</div>

Every error message, and what it means: [provider errors](/blog/guides/provider-errors/).

## 7. Let the player try a backup copy

Big playlists often carry the same channel more than once, in HD, FHD or 4K. Tuned's **Switch to a backup copy** (in **Settings › When a channel fails**, on by default) plays another copy when one stops working, and **Reconnect automatically** starts a dropped stream again where it was. For live sports, the **Tune** button on Live events tries each channel showing the game until one plays.

## 8. Change the user agent

Some servers only answer certain players, and refuse the rest. If channels fail in one app but play in another, change the name the player gives the server: **Settings › Updates and advanced › User agent**. Try VLC or one of the others in the list, then play a channel again.

<figure><img src="/blog/img/guides/user-agents.webp" alt="Tuned's User agent choices, with Tuned, VLC and other players' names and a custom option" width="270" height="586" loading="lazy"><figcaption>Settings › Updates and advanced › User agent.</figcaption></figure>

More in [changing the user agent](/blog/guides/user-agent/).

## 9. Buffering? Check the speed, then the buffer

Most HD channels need about 10 Mbps and 4K about 25, steadily. Run a speed test on the TV box itself, not your phone. If the speed is fine, set **Settings › Playback › Buffering** to **Smooth (fewer stalls)**: channels take a moment longer to open, but short dips in speed no longer stop the picture. Full list: [fixing buffering](/blog/guides/buffering/).

<figure><img src="/blog/img/guides/settings-page-playback.webp" alt="Tuned's Playback settings, with buffering and other playback options" width="270" height="586" loading="lazy"><figcaption>Settings › Playback, where the buffering mode lives.</figcaption></figure>

## 10. Guide empty or wrong?

Channels play but the guide is blank or an hour off? That's a guide problem, not a stream problem. Xtream and MAC portals bring their own guide; an M3U playlist needs a guide link. A channel that's an hour off can be fixed on its own from its guide's calendar button. See [the TV guide is empty](/blog/guides/blank-tv-guide/).

## Still stuck?

On a phone or tablet, **Settings › Updates and advanced › Report a problem** sends what happened, the recent log and your device's details to us, with logins removed. On a TV, **Logs** in the same place shows what went wrong.

<figure><img src="/blog/img/guides/browse-first-download-tv.webp" alt="Tuned getting channels ready on a TV, with the numbered steps beside a short explanation" width="640" height="360" loading="lazy"><figcaption>On a TV, the same steps, so you always know what it's doing.</figcaption></figure>

Tuned is a player for your own playlist and includes no channels. When a provider's server is down, no player can fix it; but Tuned keeps your last good playlist and guide, so the app always opens.
