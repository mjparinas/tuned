---
title: How to fix buffering in Tuned
seoTitle: "How to fix IPTV buffering in Tuned"
description: Why IPTV streams stall, and the settings and checks that fix most buffering, from buffering modes to backup copies.
date: 2026-11-19
kind: guide
---

Buffering means the stream isn't arriving as fast as it plays. It's usually the provider's server or the internet between you and it, sometimes the device. Here's how to narrow it down and fix it.

## 1. Is it one channel or all of them?

- **One channel:** that copy of the channel is struggling. See "Backup copies and quality" below.
- **Every channel:** it's your connection, your device, or the provider's server as a whole. Read on.

## 2. Pick a buffering mode

**Settings › Playback › Buffering** has three modes:

- **Fast start**: channels open quickest. Best on fast, steady internet.
- **Balanced**: the default.
- **Smooth (fewer stalls)**: holds more video before playing, so short dips in speed don't stop the picture. Channels take a moment longer to open.

If channels stall now and then, try Smooth first.

<img src="/blog/img/guides/playback-settings.webp" alt="Tuned's Playback settings, with Buffering set to Balanced" width="270" height="586" loading="lazy">

## 3. Let Tuned reconnect for you

**Auto-reconnect** (Settings › When a channel fails, on by default) retries a dropped stream up to five times, with "Reconnecting… (2 of 5)" on screen, and starts again by itself when your connection comes back.

## 4. Backup copies and quality

Providers often list a channel several times: "HD", "FHD", "4K", "(Backup)". When one copy dies, Tuned can switch to another by itself (**Switch to a backup copy**, on by default). If a channel buffers, the **quality** button in the player's top row lists its other copies; a lower quality needs less speed.

## 5. Check your connection

- A 1080p stream needs a steady 10 Mbps or so, and 4K needs 25 or more.
- Wi-Fi far from the router, or on a crowded 2.4 GHz band, is the most common cause. Try a cable on a TV box, or the 5 GHz band.
- A VPN adds a hop. Try with it off, or with a server closer to you.

## 6. Only one stream at a time

Most providers allow a set number of connections per account. If someone else is watching on the same login, or another app is still connected, the server can slow or drop you. Tuned itself never uses more than one connection: downloads, recordings and channel scans all wait while you watch.

## 7. On a TV box

- **Settings › Appearance › Lighter mode** helps boxes with little memory. Automatic turns it on by itself after freezes.
- **Match frame rate** and **Tunnelled playback** (Settings › Playback) can help 4K and 60 fps sport play smoothly. See [Nvidia Shield settings](/blog/guides/nvidia-shield/).

## 8. Still buffering?

If every player buffers at the same time of day, it's usually the provider's server at peak hours. Tell them, with the times. **Settings › Updates and advanced › Report a problem** collects the details a fix needs, without your login.

See also [provider errors](/blog/guides/provider-errors/).
