# Player

**Media3 ExoPlayer** in `PlayerActivity`, full screen, system bars hidden. A tap shows the standard controls, a back button (Melvin, 2026-11-17) and the title (channel name with what's on now and next, or the movie or episode name); they hide again after the timeout in Settings (3 seconds by default).

**Controls.** Play and pause, seek bar and skip back and forward (on-demand only; lengths in Settings), previous and next (the next channel in the same category for live, the next episode for shows), the CC button, and the settings menu with audio track and speed.

**Player tools** (Melvin, 2026-11-17). Buttons at the top right, shown with the controls:

- **Channels** (live, when the category has more than one): the category's channels down the right with what's on now; picking one switches on the same connection.
- **Aspect**: Fit, Zoom to fill (crops) and Stretch, in turn. The last choice is remembered.
- **Quality**: the stream's own renditions plus Auto. Most IPTV channels are a single TS rendition, so it says so instead of opening an empty list.
- **Stream info**: resolution, frame rate, codecs, bitrate when the stream says, audio channels and language, and the buffer, refreshed every second.
- **Lock**: ignores every touch and back, so a pocket or a child can't change anything. A tap shows the unlock button for three seconds.

**Captions** (Melvin, 2026-11-17: "customizing CC controls in the settings"). Settings › Captions sets on or off, language, size, font, bold, text colour, edge style and colour, background colour and opacity, distance from the bottom, and whether to ignore the stream's own styling. A live preview shows the result. The CC button changes captions for the current video only.

**Subtitle timing** (Melvin, 2026-11-17). A toolbar button moves the captions earlier or later in steps of 0.1 s and 1 s, up to 30 s either way, for files whose captions don't match the voices. Media3 reads captions as the stream loads, so a `SubtitleParser.Factory` wrapper adds the offset to every cue, and changing it reloads the stream at the same spot (the old connection closes first, so there is still only one). The offset lasts until the player closes, because a bad match belongs to one file. It works on captions Media3 parses (SRT, WebVTT, SSA, TTML, including those inside MKV, MP4 and HLS); CEA-608 captions carried inside a live TS stream aren't moved. It is unavailable while casting, where the TV draws the captions.

**Skip intro** (Melvin, 2026-11-17). IPTV servers don't say where an episode's intro is, so a "Skip intro" button shows at the bottom right during the first part of each episode and jumps ahead by Settings › Player › Skip intro button (85 s by default; Off hides it). Tuned also learns each show: a skip forward by hand near the start (from the first 4 minutes, by 10 s or more, landing within the first 6) is saved as that show's intro, and taps of skip-forward a few seconds apart add up to one. From then on the button shows from 15 s before that intro starts until it ends, and jumps to its end. The learned intros live in their own small DataStore (`IntroStore`), outside settings and backups. Only episodes get the button, and not while casting.

**Audio language.** Settings › Playback › Preferred audio language; the player picks a matching track when the stream has one, and the settings menu switches tracks while watching.

**Start speed.** TS streams use the extractor flags that allow non-IDR key frames and detect access units, so live channels start sooner. Buffering has three modes (Fast start, Balanced, Smooth). OkHttp is the data source, shared with the rest of the app, with an optional custom user agent.

**Live streams that drop** are retried quietly up to three times (1.5 s, 3 s, 4.5 s) before an error shows. Falling behind the live window jumps back to live.

**Resume.** Movies and episodes save their position every 10 seconds and on leaving; reopening resumes after 30 seconds in, unless 95% watched. Shows mark watched episodes.

**Cast** (Chromecast, 2026-11-17). The cast button sits with the player tools. Casting uses the default media receiver. When a cast starts the phone stops its own stream, so the provider only ever sees one connection, and the TV plays the channel. Next, previous, swipes and the mini guide change the channel on the TV, and the play and pause buttons control the TV. Ending the cast resumes on the phone, at the same point for films and episodes. The default receiver can't play raw MPEG-TS, so Xtream live links are sent as the same channel's `.m3u8`; films in formats the receiver lacks (MKV, for example) may not play. DLNA and UPnP TVs (2026-11-17) appear in the same picker, found with an SSDP search on the local network while the player is open; phones without Google Play services list only those. A DLNA TV is sent the link as it is (most play MPEG-TS and MP4), with the same one-connection rule. A TV that can't seek starts films from the beginning.

**Picture-in-picture** when leaving the app while playing (Settings, on by default). Leaving without it ends playback.

## Gestures and reconnecting (2026-11-17)

Melvin asked for auto-reconnect and player gestures. Both are on by default and each has a switch in Settings › Player.

- **Gestures** (`PlayerGestures`), as most phone players do them:
  - drag up or down on the left half for brightness, on the right half for volume;
  - double-tap the left or right third to skip back or forward (films and episodes);
  - swipe sideways to change channel (live TV);
  - a single tap still shows the controls.

  Brightness goes back to the system's when the player closes.

  While brightness or volume changes, a thin bar with its icon shows at that edge, brightness on the left and volume on the right, and fades out shortly after the finger lifts (`LevelBars`; Melvin, 2026-11-17: "subtle bars on the left and right side with a brightness and sound icon" instead of text in the middle). The volume icon turns to muted at zero. The bars read out the percentage to screen readers.

- **Reconnect.** A dropped live stream, or a network error on a film or episode, is retried up to five times with a growing pause, and "Reconnecting… (2 of 5)" shows meanwhile. The count resets once it plays again. When the phone's connection comes back (`ConnectivityManager` default network), a stopped stream starts again by itself, and `prepare()` keeps a film's position. It still uses one connection at a time, never a second stream.

**Ratings and trailers** (2026-11-17). From The Movie Database, with the person's own free key in Settings › Ratings (a v3 key or a v4 read token; it is never committed, and it rides in the encrypted backup with the other settings). Titles are cleaned of the provider's tags (`EN -`, `4K`, `[FHD]`) and the year is used when the name has one. Without a key nothing is asked and the page says where to add one.

**Film page** (Melvin, 2026-11-17: "when i click on a movie, it should take me to the movie details page", and the rating at the bottom right of each poster). Tapping a film's poster, in the grid or in search, opens its page: the backdrop across the top fading into the page, the poster and title over its lower edge, rating, year and runtime, genres, Play (Resume when started), Trailer (YouTube), the summary and a row of the cast with photos. Play now stays in the poster's long-press menu. Each poster in Movies and Shows shows its TMDB rating in the bottom right corner (TMDB's own score; IMDb's needs a separate service). A show's page has the same details above its episodes.

**Kept on the device** (Melvin, 2026-11-17). Every TMDB answer is saved in the database (`metadata` table): the search answer for a poster's rating, and the full details once a page is opened. A page or badge seen once loads from the device, works offline, and is asked again only after a month (a week for "not found"). Artwork (posters, backdrops, cast photos, logos) goes through Coil's 512 MB disk cache in app storage rather than the cache folder, so Android doesn't empty it when space is low.

## Backup copies (2026-11-17)

From the app audit, Melvin picked "backup streams per channel". Providers list one channel several times ("ESPN", "US| ESPN HD", "US| ESPN FHD", "ESPN (Backup)"), and when one copy dies another usually works.

- **Which channels are copies** (`core/ChannelCopies`, with tests). The name minus a known country tag in front, picture and codec words (HD, FHD, 4K, HEVC, 1080p, 60fps), backup markers ("(B)", "[Backup]") and decorations ("ᴴᴰ"). Everything else stays, so "ESPN 2" and "ESPN News" are never copies of "ESPN", and "ABC - News" isn't a copy of "CBS - News" (ABC isn't a country). Copies must be from the same country, or one has no country tag. They are found through the full-text index (`ItemDao.copyCandidates`), then matched exactly, so no new column or migration was needed. Hidden channels are never used as copies.
- **When.** A live channel that dies for good: one that never showed a picture gets one retry (instead of five) so a dead channel is left quickly; one that was playing gets the usual retries. Then the copies are tried best first, ranked like auto-tune (recently dead last, then what played before, then the sharpest), each with the auto-tune wait for a picture. The first that plays takes the channel's spot in the list, so next and previous still flip through the same category. "Not working. Trying a backup copy (1 of 3)…" shows meanwhile, and "Switched to US| ESPN FHD" once one plays. Flipping to another channel stops the search.
- **One connection.** The old stream is stopped before a copy starts; nothing is fetched ahead.
- Settings › Player › **Switch to a backup copy**, on by default. Not used for auto-tune (which has its own list), recordings or while casting.

## TV playback (2026-11-17)

From the app audit, Melvin picked "match frame rate and Dolby audio fallback".

- **Match frame rate** (Settings › Player, on by default). When Media3 knows the video's frame rate, the player window asks for the display mode at the same resolution whose refresh rate equals it, or failing that twice it (`core/FrameRates`, with tests: 50 fps on 50 Hz, 25 on 50, 23.976 on 23.976, 29.97 on 59.94). It goes through `WindowManager.LayoutParams.preferredDisplayModeId`, so the TV returns to its usual mode when the player closes. Nothing happens on screens without such modes, or while casting.
- **Silent channels** (`player/AudioCheck`). Many sports channels carry Dolby (AC-3, E-AC-3) or DTS sound that phones often can't decode. If the stream has another audio track the device can play, Tuned picks it. If it has none, a note names the format ("No sound: this device can't play this channel's Dolby Digital (AC-3) audio") once per channel, instead of leaving a silent picture. Decoder fallback is on, so a device whose first decoder fails tries its next one.
- **Not done: a software Dolby decoder.** It needs Media3's FFmpeg extension, which Google doesn't publish. The community build (Jellyfin's) stops at Media3 1.9, and mixing versions risks crashes on exactly the channels it's meant to fix. Building FFmpeg in CI would add several megabytes per device type and minutes to every build. To revisit if silent channels turn out to be common.
