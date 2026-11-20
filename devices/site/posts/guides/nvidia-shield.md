---
title: Tuned on Nvidia Shield TV: the best settings
seoTitle: "Tuned IPTV on Nvidia Shield TV: the best settings"
description: Get smooth 4K and sport on a Shield with frame rate matching, tunnelled playback and the right buffering, plus casting tips.
date: 2026-11-19
kind: guide
---

The Nvidia Shield TV is one of the most capable Android TV boxes, and Tuned runs on it like on any Android TV: same app, same remote shortcuts. Set-up is in [Tuned on Android TV](/blog/guides/android-tv/); this page is about getting the most out of a Shield.

## Recommended settings

In **Settings › Playback**:

- **Match frame rate**: leave it on. The Shield switches its output to the video's frame rate (24 fps films, 50 or 60 fps sport, or twice the rate where the exact one isn't offered), and goes back when the player closes.
- **Tunnelled playback**: try it on. The Shield's video hardware keeps picture and sound in step itself, which can make 4K and fast sport smoother. If you notice stutter or the sound drifting, turn it off again; Tuned uses it only when both the picture and the sound of a stream support it.
- **Buffering**: **Balanced** suits most connections. Pick **Smooth (fewer stalls)** if your provider's streams pause now and then, or **Fast start** on fast, steady internet for quicker channel changes.
- **Sound delay**: if your soundbar or receiver lags, move the sound up to 500 ms. (It doesn't apply while tunnelled playback is on, since the hardware keeps its own clock.)

## Sound formats

Many sports channels carry Dolby Digital or DTS sound. If a stream has a sound track the device can't play, Tuned switches to another one when the stream has it, and otherwise says which format it can't play rather than leaving you with silence.

## Casting to a Shield

The Shield has Chromecast built in, so you can also cast to it from Tuned on your phone. Your phone stops its own stream first, so your provider sees one connection. Films you've downloaded are streamed from your phone over Wi-Fi in a format the Shield's cast player reads, with surround sound sent as stereo. See [casting](/blog/guides/casting/).

## Plenty of space for downloads

A Shield with spare storage is a good home for [downloads and recordings](/blog/guides/recordings-and-downloads/). They play from the box, never the stream, and wait whenever something is playing.
