# Player

**Media3 ExoPlayer** in `PlayerActivity`, full screen, system bars hidden. A tap shows the standard controls and the title (channel name with what's on now and next, or the movie or episode name); they hide again after the timeout in Settings (3 seconds by default).

**Controls.** Play and pause, seek bar and skip back and forward (on-demand only; lengths in Settings), previous and next (the next channel in the same category for live, the next episode for shows), the CC button, and the settings menu with audio track and speed.

**Captions** (Melvin, 2026-11-17: "customizing CC controls in the settings"). Settings › Captions sets on or off, language, size, font, bold, text colour, edge style and colour, background colour and opacity, distance from the bottom, and whether to ignore the stream's own styling. A live preview shows the result. The CC button changes captions for the current video only.

**Audio language.** Settings › Playback › Preferred audio language; the player picks a matching track when the stream has one, and the settings menu switches tracks while watching.

**Start speed.** TS streams use the extractor flags that allow non-IDR key frames and detect access units, so live channels start sooner. Buffering has three modes (Fast start, Balanced, Smooth). OkHttp is the data source, shared with the rest of the app, with an optional custom user agent.

**Live streams that drop** are retried quietly up to three times (1.5 s, 3 s, 4.5 s) before an error shows. Falling behind the live window jumps back to live.

**Resume.** Movies and episodes save their position every 10 seconds and on leaving; reopening resumes after 30 seconds in, unless 95% watched. Shows mark watched episodes.

**Picture-in-picture** when leaving the app while playing (Settings, on by default). Leaving without it ends playback.
