---
title: How much data does IPTV use? SD, HD and 4K per hour
seoTitle: "How much data does IPTV use per hour? SD, HD, Full HD and 4K explained"
description: IPTV uses roughly 1 GB an hour in SD, 2 to 3 GB in HD and up to 11 GB in 4K. What decides it, how to check yours, and how to watch on mobile data or a capped plan.
date: 2026-11-27
kind: guide
---

<figure class="cover"><img src="/blog/img/posts/data-usage.jpg" alt="About 1 GB an hour for SD, 2 for HD, 3 for Full HD and 7 to 11 for 4K, per stream. Tuned only ever plays one." width="1200" height="760"></figure>

If you watch on mobile data, a hotspot, satellite internet or a plan with a monthly cap, the first question about IPTV is how much it uses. The short answer is **about 1 GB an hour in standard definition, 2 to 3 GB in HD, and 7 to 11 GB in 4K**. The long answer depends on your provider, and you can work out your own in a minute.

Tuned is a player for your own playlist and includes no channels. The data numbers here are about the streams your provider sends; the player decides how many of them you're using at once.

## The quick table

| Picture         | Typical stream  | Per hour       | A 2-hour film   | A month at 3 hours a day |
| --------------- | --------------- | -------------- | --------------- | ------------------------ |
| SD (576p)       | 1.5 to 2.5 Mbps | 0.7 to 1.1 GB  | 1.4 to 2.2 GB   | 60 to 100 GB             |
| HD (720p)       | 3 to 5 Mbps     | 1.4 to 2.3 GB  | 2.7 to 4.5 GB   | 120 to 200 GB            |
| Full HD (1080p) | 5 to 8 Mbps     | 2.3 to 3.6 GB  | 4.5 to 7.2 GB   | 200 to 320 GB            |
| 4K              | 15 to 25 Mbps   | 6.8 to 11.3 GB | 13.5 to 22.5 GB | 600 GB to 1 TB           |

These are typical ranges, not promises. Two "HD" channels from the same provider can differ by half, and sport at 50 or 60 frames a second sits at the top of each range.

## How to work it out yourself

Data per hour comes straight from the stream's **bitrate**, the number of megabits it sends every second:

**GB per hour = Mbps × 0.45**

(3,600 seconds an hour, divided by 8 bits in a byte, divided by 1,000 MB in a GB.) So a 6 Mbps Full HD channel uses about 2.7 GB an hour, and a 20 Mbps 4K channel about 9 GB.

To see what your channels actually send:

- **In Tuned**, tap the screen while a channel plays. The pills under the title show the picture size, frame rate and sound, so you know which row of the table you're in.
- **On your router or phone**, check the data used before and after watching for 10 minutes, and multiply by 6.

<figure><img src="/blog/img/guides/player-controls.webp" alt="Tuned's player controls with back and forward buttons, play and pause, and the seek bar" width="640" height="295" loading="lazy"><figcaption>Tap the picture to see the controls and the stream's details.</figcaption></figure>

## What changes how much you use

- **The picture quality** your provider sends. It's the biggest factor by far: 4K is about ten times SD.
- **The codec.** HEVC (H.265) needs roughly half the data of the older H.264 for the same picture. Your provider chooses this, not the player.
- **Motion.** Sport and action use more than a news desk, especially on channels that adapt their bitrate.
- **How many streams play at once.** Two TVs watching two channels use twice the data. So does a player that shows a live preview of the highlighted channel, or downloads while you watch.

That last one is where the player matters. A live preview is a second full stream, quietly using data the whole time you browse.

## Tuned plays one stream, always

Tuned never opens a second stream. There's no live preview: the channel list shows what's on in words and pictures from the guide. Changing channel closes the old stream before the new one opens. Downloads, recordings and channel scans wait while anything plays. So the data you use is the channel you're watching, and nothing else. More in [connections explained](/blog/guides/iptv-connections-explained/).

## The playlist and guide are small

Downloading the channel list and the TV guide is a small fraction of watching. A big guide is usually a few megabytes to a few tens of megabytes. Tuned shows the real size as it downloads, and refreshes in the background every few hours rather than each time you open the app.

<figure><img src="/blog/img/guides/browse-refresh-bar.webp" alt="Tuned's refresh bar reading Step 5 of 7, Downloading the guide, 6.2 MB of 19 MB" width="640" height="67" loading="lazy"><figcaption>The real size of each download, as it happens.</figcaption></figure>

Posters and film details are kept on the device after the first time, so browsing a library you've seen before costs almost nothing.

## Watching on mobile data or a capped plan

1. **Turn on Wi-Fi only.** In **Settings › Guide and refresh**, Wi-Fi only keeps refreshes and downloads off mobile data. Watching still works on mobile data when you choose to.
2. **Pick a lighter copy of the channel.** Many playlists carry the same channel in SD, HD, FHD and 4K. The **quality** button in the player's top row lists the copies, so on a phone screen you can switch to HD and use a third of the data of 4K.
3. **Download before you leave.** Films and episodes saved at home on Wi-Fi play from the device, with no data at all. See [watch on a plane](/blog/guides/watch-iptv-offline/).
4. **Cast instead of streaming on the phone.** When you cast to a TV, the TV fetches the stream over your home internet and the phone stops its own.

<div class="shots">
<figure><img src="/blog/img/guides/settings-page-guide.webp" alt="Tuned's Guide and refresh settings with refresh times, guide days, time offset and extra guide links" width="270" height="586" loading="lazy"><figcaption>Settings › Guide and refresh, with Wi-Fi only.</figcaption></figure>
<figure><img src="/blog/img/guides/browse-downloads.webp" alt="Tuned's Downloads page with how much space is used and left, shows and films" width="270" height="586" loading="lazy"><figcaption>Downloaded at home, watched with no data.</figcaption></figure>
</div>

## How fast does your internet need to be?

Speed and data are different things. Speed is how fast the stream must arrive; data is how much arrives in total. As a rule, give each stream about **twice its bitrate** in steady speed: about 10 Mbps for HD and 25 Mbps for 4K. If your speed test is fine but channels still stall, see [fixing buffering](/blog/guides/buffering/) and [IPTV not working?](/blog/guides/iptv-not-working/)

## A month of IPTV, roughly

- **Evening viewer** (3 hours a day in Full HD): about 200 to 320 GB a month.
- **Sports fan** (5 hours a day, some of it 4K): 500 GB to 1 TB.
- **Light viewer on a phone** (an hour a day in HD): 40 to 70 GB.

If your plan has a cap, check where you are a week in and adjust: one 4K match can use more than a whole evening of HD.
