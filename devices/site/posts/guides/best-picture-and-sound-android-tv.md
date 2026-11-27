---
title: The best picture and sound for IPTV on Android TV and Google TV
seoTitle: "Best IPTV picture and sound settings on Android TV: frame rate, 4K, Dolby, lip sync"
description: Smooth sport, sharp 4K, sound in sync and nothing cut off at the edges. The settings that matter on an Android TV or Google TV box, in the TV, the box and the player.
date: 2026-11-27
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/picture-sound.jpg" alt="Tuned's Playback settings on a phone beside the Calibrate screen tool on a TV" width="1200" height="760"></figure>

You've paid for a good TV and a good box. Then the football judders, the edges of the picture are cut off, and the commentator's lips don't match the words. None of that is your provider's fault, and all of it can be fixed in a few minutes. This guide works from the cable up: the connection, the TV, the box, then the player.

Tuned is a player for your own playlist and includes no channels. The settings below are in Tuned on Android TV and Google TV, and most of the advice applies to any player.

## 1. Start with the connection

Picture problems are often delivery problems in disguise. A stream that arrives unevenly makes the player drop to a lower quality or pause to buffer.

- **Use a cable to the router** if you can. Wi-Fi in a living room shares the air with every phone and the neighbours' routers; a cable doesn't.
- **On Wi-Fi, use 5 GHz** and keep the box out of a closed cabinet.
- **Give each stream about twice its bitrate** in steady speed: around 10 Mbps for HD and 25 Mbps for 4K. Test on the box, not your phone.

What the numbers mean for your data: [how much data IPTV uses](/blog/guides/how-much-data-does-iptv-use/).

## 2. Set up the TV itself

- **Picture mode:** choose Movie, Cinema or Filmmaker mode rather than Vivid or Dynamic, which over-sharpen and push colours. For sport, a Sport mode can help, but turn its motion smoothing down if faces look plastic.
- **Motion smoothing** (each brand has its own name for it) is a matter of taste for sport and makes films look like soap operas. Try it both ways on a match.
- **Game or PC mode** cuts the TV's own processing delay, which helps lip sync on some sets.
- **Overscan:** many TVs zoom the picture slightly by default. Look for "Just scan", "Screen fit" or "Fit to screen" in the TV's picture size settings. Tuned's own calibration (below) handles the rest.

## 3. Set up the box

On an Android TV or Google TV box (a Shield, a Google TV Streamer, an onn 4K box):

- **Display resolution:** set it to your TV's native resolution, usually 4K, rather than Auto if Auto picks 1080p.
- **HDR:** leave the box's HDR setting on its automatic choice, so it switches only for content that has HDR.
- **Sound:** if you use a soundbar or AV receiver, set the box's surround sound to automatic, so it sends Dolby to equipment that can play it.

Choosing a box: [the best streaming box for IPTV](/blog/roundups/best-streaming-box-for-iptv/).

## 4. In the player: smooth motion with frame rate matching

Most judder comes from a mismatch: the channel is 50 frames a second, the TV runs at 60 Hz, and every few frames one is shown twice. Sport, where the camera pans constantly, shows it worst.

**Match frame rate** (on by default in Tuned, under **Settings › Playback**) asks the TV to switch to a refresh rate that fits the channel: 50 Hz for a 50 fps channel, 23.976 Hz for a film, or a rate that's exactly double. The TV goes back to its usual mode when the player closes. The screen may blank for a second as it switches; that's the TV changing mode, and it's the sign it's working.

<figure><img src="/blog/img/guides/settings-page-playback.webp" alt="Tuned's Playback settings with audio language, resume, autoplay, skip intro, pause and rewind live TV and buffering" width="270" height="586" loading="lazy"><figcaption>Settings › Playback.</figcaption></figure>

### Tunnelled playback, for smoother 4K and 60 fps

On Android TV, **Settings › Playback › Tunnelled playback** hands the picture and sound straight to the TV's own video hardware, which keeps them in step itself. On boxes that support it well, 4K and 50 or 60 fps sport play more smoothly. It's off by default because some TVs report support and then stutter, so try it, watch a match, and keep it only if it's better. Tuned only uses it when the box says the channel's picture and sound both support it, so turning it on can't stop a channel playing.

## 5. Sound: no more silent channels, and lips in sync

**Silent picture?** Many sports channels carry Dolby Digital, Dolby Digital Plus or DTS sound, which some devices can't decode. Tuned picks another sound track when the channel has one, and carries its own Dolby and DTS decoder for devices that lack one. If a channel still has no sound, Tuned tells you which format it is rather than leaving you guessing.

**Lips out of sync?** Soundbars, Bluetooth speakers and some TVs' own processing delay the sound. **Settings › Playback › Sound delay** moves the sound up to half a second earlier or later than the picture. Set it once per device; it doesn't sync to your other devices, because each one's speakers lag by their own amount.

**The right language:** **Settings › Playback › Preferred audio language** picks a matching sound track when a stream has more than one.

## 6. Nothing cut off at the edges: calibrate the screen

Some TVs crop the edges of the picture (overscan), which cuts off the edges of menus too. **Settings › Appearance › Calibrate screen** shows marks in each corner: move and stretch until every mark sits just inside the edge of your TV. Every Tuned screen then fits your TV exactly.

<figure><img src="/blog/img/guides/settings-tv-calibrate.webp" alt="Tuned's Calibrate screen on a TV, with corner marks and sliders to move and stretch the picture" width="640" height="360" loading="lazy"><figcaption>Settings › Appearance › Calibrate screen.</figcaption></figure>

## 7. Pick the best copy of the channel

Providers often list the same channel several times: SD, HD, FHD, 4K. The **quality** button in the player's top row lists the best copy of each quality and switches to the one you pick, at the same spot. Tap the picture to see what you're getting: the pills under the title show the resolution, frame rate and sound.

<figure><img src="/blog/img/guides/player-controls.webp" alt="Tuned's player controls with back and forward buttons, play and pause, and the seek bar" width="640" height="295" loading="lazy"><figcaption>Tap the picture for the controls and the stream's details.</figcaption></figure>

If the 4K copy buffers and the FHD one doesn't, your connection is the limit, not the TV. Settle on FHD for that channel; on a good set it looks very close.

## 8. A smooth, quick app on a slower box

Older or cheaper boxes can feel sluggish in menus. **Settings › Appearance › Lighter mode** removes fades and animations and loads lighter pictures, so browsing stays quick. It's on automatically on low-memory devices.

<figure><img src="/blog/img/guides/settings-page-appearance.webp" alt="Tuned's Appearance settings with theme, wallpaper colours, accent colour, pure black, lighter mode and warm next screens" width="270" height="586" loading="lazy"><figcaption>Settings › Appearance, with Lighter mode.</figcaption></figure>

## The checklist

- Cable to the router, or 5 GHz Wi-Fi
- TV in Movie or Filmmaker mode, overscan off
- Box at the TV's native resolution, HDR and surround on automatic
- Match frame rate on
- Tunnelled playback tried, kept if smoother
- Sound delay set if you use a soundbar or Bluetooth
- Screen calibrated
- Best working copy of your favourite channels

More remote tips: [Tuned on Android TV](/blog/guides/android-tv/) and [the Nvidia Shield guide](/blog/guides/nvidia-shield/).
