---
title: How to use subtitles and captions in Tuned
seoTitle: "How to use IPTV subtitles and captions in Tuned"
description: Turn captions on, style them, fix their timing, and find subtitles for films and episodes that don't have any.
date: 2026-11-19
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/subtitles.jpg" alt="Tuned's Captions settings with a live preview on a phone and a TV" width="1200" height="760"></figure>

## Turn captions on

Tap the screen while something plays and tap **CC**. It changes captions for that video only. To have them on by default, and pick the language, go to **Settings › Captions**.

## Make them easier to read

**Settings › Captions** sets the size, font, bold, text colour, edge style and colour, background colour and opacity, and distance from the bottom, with a live preview. You can also tell Tuned to ignore the stream's own styling.

## Find subtitles for a film or episode

Many streams come without subtitles. The **subtitle search** button, just before CC in the player, finds them on OpenSubtitles.com:

- Matches are in your caption language, best first: ones written by a person before machine translations, then the most downloaded.
- Pick one and the video reloads at the same spot with it.
- With captions on, Tuned finds the best match by itself for a video with no subtitles (**Settings › Playback › Subtitles › Find subtitles automatically**, on by default).

Each file is kept on your device, so reopening a film shows its subtitles with no download. OpenSubtitles allows a few downloads a day without an account; sign in to an OpenSubtitles account in **Settings › Playback › Subtitles** for more. That sign-in is stored encrypted.

Subtitle search works for films and episodes, not live TV.

## Fix subtitles that don't match the voices

Tap the **subtitle timing** button beside CC to move captions earlier or later, in steps of 0.1 or 1 second, up to 30 seconds either way. The change lasts until you close the player, since a bad match belongs to one file. It works on SRT, WebVTT, SSA and TTML captions, including those inside MKV, MP4 and HLS; captions carried inside a live TV stream can't be moved.

<img src="/blog/img/guides/player-controls.webp" alt="Tuned's player controls, with subtitle search, subtitle timing, CC and settings at the bottom right" width="640" height="295" loading="lazy">

## On a TV

Subtitles found with subtitle search go to a Chromecast with the video. See [casting](/blog/guides/casting/).

## Audio language

Not subtitles, but close: **Settings › Playback › Preferred audio language** picks a matching sound track when a stream has one, and the player's settings button switches tracks while you watch.

Captions, TalkBack, large text and the remote: [accessible IPTV](/blog/guides/accessible-iptv/).
