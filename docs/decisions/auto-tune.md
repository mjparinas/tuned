# Auto-tune

Melvin, 2026-11-17: finding a live event such as a UFC fight "takes me forever". A search turns up close to a hundred channels across Sports, PPV and country categories, and about half of them don't work. "I really just want to watch the first good quality stream that's in English", found the way a radio's auto-tune finds a station.

## How it works

1. **Search finds events, not only channels.** On Live TV, a search matches channel names and also the guide's programmes on now or within the hour, across every category. "UFC" finds the PPV channel showing tonight's card even when its name is "US| PPV 7".
2. **Auto-tune ranks what the search found** (`core/Tuner.kt`, with tests):
   1. channels that failed in the last 6 hours go last (they get another chance after that);
   2. English first: US, UK, CA, EN, AU, IE and NZ tags, unless the name says another language ("Spanish", "Dubbed"); other countries' channels go after;
   3. channels that played in the last week before unknown ones;
   4. a guide match before a name-only match;
   5. sharper first: the height measured last time, or 4K, FHD, HD and SD in the name.
3. **The player tries them one at a time** (`player/AutoTune.kt`). A channel that errors, or shows no picture within 10 seconds (Settings › Auto-tune), is noted as dead and the player moves to the next. The first one that draws a picture is noted as good and plays. Next and previous in the player move through the same list. If a tuned channel drops later and its own retries fail, the player moves on to the next one too.
4. **It remembers** (`channel_health` table): when each channel last played or failed, and its picture height. The next search starts on a known-good channel.

Settings › Auto-tune has two choices: how long to wait for a picture, and the lowest picture to accept (any, 540p, 720p or 1080p). A channel below that is remembered as a fallback and played only if nothing sharper works.

## One connection at a time

Providers limit how many streams an account may have open, and Melvin doesn't want to risk a ban. Auto-tune never opens two channels at once: moving on releases the current stream before the next starts, and nothing is fetched ahead. For the same reason there is no multi-view.

## Not done (yet)

- Checking channels' health in the background. It would open connections while the person is watching something else. A scan started by hand does it instead ([channel scan](channel-scan.md)), and auto-tune skips the channels it hides.
- Matching the guide's language attribute. The real guide rarely sets it, so the country tag is the better signal.
