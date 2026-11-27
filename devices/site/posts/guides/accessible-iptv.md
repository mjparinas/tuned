---
title: "Accessible IPTV: captions, TalkBack, large text and the remote"
seoTitle: "Accessible IPTV on Android: captions, subtitles, TalkBack, large text and remote control"
description: How to make IPTV easier to see, hear and control on Android phones and TVs. Caption styling, subtitle search, TalkBack, large text, reduced motion, languages and the remote.
date: 2026-11-27
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/accessible.jpg" alt="Tuned's Captions settings with a live preview, beside its Appearance settings with theme, accent and lighter mode" width="1200" height="760"></figure>

IPTV players are often built for one kind of viewer: someone with good eyes, a steady hand and a remote they know by heart. Plenty of people don't fit that, and plenty more just want bigger captions on a Sunday night. This guide covers what makes a player accessible and how to set each part up in Tuned, on a phone, a tablet or a TV.

Tuned is a player for your own playlist and includes no channels. Captions and audio tracks depend on what your provider's streams carry; the player decides how well you can use them.

## Captions you can actually read

Turn captions on for one video with the **CC** button in the player, or for everything in **Settings › Captions**, where you also choose the language. Then make them readable. Settings › Captions has:

- **Size**, from small to very large, with a live preview.
- **Font** and **bold**.
- **Text colour**, **edge style** and **edge colour**, from the standard caption palette, each colour with its name.
- **Background colour and opacity**, from none to a solid box behind every line.
- **Distance from the bottom**, so captions clear a TV's frame or a soundbar.
- **Ignore the stream's own styling**, for channels that send tiny or oddly coloured captions.

<figure><img src="/blog/img/guides/settings-captions.webp" alt="Tuned's Captions settings with a live preview, Show captions, caption language, size, font, bold and text colours" width="270" height="586" loading="lazy"><figcaption>Settings › Captions, with a live preview.</figcaption></figure>

<figure><img src="/blog/img/guides/settings-captions-tv.webp" alt="Tuned's Captions settings on a TV, with the preview beside the options" width="640" height="360" loading="lazy"><figcaption>The same caption settings on a TV, each one a button the remote reaches.</figcaption></figure>

### When a film has no subtitles

Many streams come without them. **Subtitle search**, the button just before CC, finds subtitles for films and episodes on OpenSubtitles.com, in your caption language, human-written ones first. With captions on, Tuned finds the best match by itself for a video that has none. If a match is out of step with the voices, **subtitle timing** moves it earlier or later. More in [subtitles in Tuned](/blog/guides/subtitles/).

<figure><img src="/blog/img/guides/player-controls.webp" alt="Tuned's player controls with subtitle search, subtitle timing, CC and settings at the bottom right" width="640" height="295" loading="lazy"><figcaption>Subtitle search, timing and CC, bottom right in the player.</figcaption></figure>

### Live TV captions

Live channels carry captions only if the broadcaster sent them and your provider passed them on. When a live channel has them, CC turns them on and your styling applies.

## Hearing it clearly

- **Preferred audio language** (Settings › Playback) picks a matching sound track when a stream has several.
- **Sound delay** (Settings › Playback) fixes lips out of step with speech, common with soundbars and Bluetooth hearing devices. Set it once per device.
- **No silent channels:** when a channel's sound is in a format the device can't play, Tuned switches to another track, or tells you which format it is.

## TalkBack and Switch Access

Tuned works with Android's screen reader and switch controls without adding anything to the screen for people who don't use them:

- **Every icon-only button has a name**, and names in long lists say which row they're for ("Guide for Lakeshore TV", not "Guide" a hundred times).
- **States are spoken, not just coloured**: the programme on now, the channel playing, the current profile, a favourite.
- **Switches say on or off**, choices say which is selected, and section titles are headings, so you can jump between them.
- **The player's controls stay up while TalkBack is on**, until you tap the video again, so a swipe can reach every button. Without a screen reader, Android's "Time to take action" setting can make them stay longer.

## Large text and display size

All of Tuned's text follows Android's font size, and buttons grow to fit rather than cutting words off. Every tap target is at least 48 dp, the size Android recommends for a fingertip. Set the font size and display size in Android's display settings, and Tuned follows.

## Easier on the eyes

**Settings › Appearance** has:

- **Theme**: light, dark or the system's, and **pure black** for OLED screens and dark rooms.
- **Accent colour**, or your wallpaper's colours. Text and buttons are checked for contrast in every theme and accent.
- **Lighter mode**: no fades, no pulsing placeholders, no spinning rings. It helps on a slow TV box, and for anyone who finds motion distracting.

<figure><img src="/blog/img/guides/settings-page-appearance.webp" alt="Tuned's Appearance settings with theme, wallpaper colours, accent colour, pure black, lighter mode and warm next screens" width="270" height="586" loading="lazy"><figcaption>Settings › Appearance.</figcaption></figure>

## Everything from the remote

On a TV, every action is a button the remote can reach: no hidden menus, no long-presses you have to know about.

- **Each screen opens on the likely choice**, so OK alone usually does it.
- **Whatever the remote is on has a clear outline.**
- **Up and down move between rows; left and right adjust.** Always, on every screen.
- **Like a cable box:** up and down change channel while you watch, number keys jump to a channel, and the last-channel button flips back.
- **No typing with the remote:** sign-ins happen on your phone.

More remote tips: [Tuned on Android TV](/blog/guides/android-tv/).

## Your language

Tuned is in 11 languages, including Arabic, which draws the whole app right to left, Hindi and Simplified Chinese. It follows the language your device is set to; there's no setting to find.

<figure><img src="/blog/img/guides/language-ar-live-tv.webp" alt="Tuned's Live TV in Arabic, laid out right to left" width="270" height="586" loading="lazy"><figcaption>Tuned in Arabic, right to left.</figcaption></figure>

## The website, too

The account pages and the Channels and guide page on tunedplayer.com are checked against the WCAG 2.2 AA accessibility standard, in light and dark, at phone width and by keyboard.

## Something not working for you?

On a phone or tablet, **Settings › Updates and advanced › Report a problem** sends us what happened, in your own words, with your device's details and no logins.

Profiles for different people in the house, each with their own settings and favourites: [parental controls and profiles](/blog/guides/parental-controls-iptv/).
