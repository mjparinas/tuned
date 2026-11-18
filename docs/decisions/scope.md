# Scope: ruled out, and decided but not built yet

This file keeps the decisions that don't belong to one area yet: what Tuned will never do, and what Melvin has asked for that is still being built. When a feature below merges, its pull request moves the decision into the topic's own file and deletes the line here. CI checks that feature pull requests touch the record ([AGENTS.md](../../AGENTS.md#decision-record)).

## Ruled out

- **A second video inset over the first, and a sleep timer.** Melvin, 2026-11-17: "Never implement or suggest picture-in-picture or a sleep timer again", then, clarifying: leaving the app with the video still showing in a small window "is okay", but "a smaller video on one of the corners that shows a completely different TV show. That's not okay because that's multiple streams." So Android's picture-in-picture stays ([player](player.md)). A second channel drawn over the first is never built, and a sleep timer is never built or suggested.
- **Multi-view and live previews.** Melvin, 2026-11-17: "not sure if i'm allowed to stream multiple streams, i dont want to get banned". Providers cap connections per account, so Tuned never holds more than one stream: nothing plays two channels at once, nothing prefetches, previews or checks channels in the background. Every feature that opens a stream (auto-tune, backup copies, scans, downloads, recording) waits for or releases the one connection.
- **AirPlay.** Apple's protocol has no supported Android sender, and the reverse-engineered ones break on live streams (2026-11-17). Chromecast and DLNA cover TVs instead ([player](player.md)).
- **IMDb ratings through OMDb.** Offered and then dropped (2026-11-17): ratings stay on TMDB, so one free key is all a person needs.

## Decided, being built

Each line names Melvin's words and date. Move it into the topic's file when it merges.

- **Never hide sports, PPV and event channels** (Melvin, 2026-11-17: worried auto-tune or the scan would hide "legitimate channels" such as pay-per-view slots with nothing on, "just in case there is no show at that time"). Scans and failed plays only mark them off air; auto-tune still tries every one, recent failures last. Empty "NO EVENT STREAMING" slots stay out of the Live TV list but auto-tune and search can still reach them. (Pull request #63.)
- **Downloads by show** (Melvin, 2026-11-17): one poster per show, then its seasons (with season posters where TMDB has them), then episodes with screenshots; the top of the page shows how much is downloaded and how much space is left on the device. (#64.)
- **Real episode names everywhere** (Melvin, 2026-11-17: "if you can find the actual name of an episode, prioritize that over showing the season number and episode number"), with TMDB's still for each episode. (#57.)
- **Offline mode** (Melvin, 2026-11-17): when the device is offline a banner says so at the top, everything cached can still be browsed, and downloads play.
- **Cache first** (Melvin, 2026-11-17: "We should focus on caching as much as we can"): posters, covers, backdrops, descriptions and cast photos and names come from the device first; TMDB is asked only for what is missing, to save time and data.
- **Actor pages** (Melvin, 2026-11-17): tapping a cast member lists every film and show they've been in, in date order, paged.
- **Live Events page** (audit item 2, picked 2026-11-17): today's sports and pay-per-view with Tune.
- **Full grid TV guide** and **profiles with a PIN lock** (Melvin, 2026-11-17).

## Offered, not decided

- Skipping whole countries or languages at import to keep the database smaller (offered 2026-11-17 with the search speed work; the speed audit didn't need it).
