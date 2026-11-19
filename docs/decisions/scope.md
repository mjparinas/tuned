# Scope: ruled out, and decided but not built yet

This file keeps the decisions that don't belong to one area yet: what Tuned will never do, and what Melvin has asked for that is still being built. When a feature below merges, its pull request moves the decision into the topic's own file and deletes the line here. CI checks that feature pull requests touch the record ([AGENTS.md](../../AGENTS.md#decision-record)).

## Ruled out

- **A second video inset over the first, and a sleep timer.** Melvin, 2026-11-17: "Never implement or suggest picture-in-picture or a sleep timer again", then, clarifying: leaving the app with the video still showing in a small window "is okay", but "a smaller video on one of the corners that shows a completely different TV show. That's not okay because that's multiple streams." So Android's picture-in-picture stays ([player](player.md)). A second channel drawn over the first is never built, and a sleep timer is never built or suggested.
- **Multi-view and live previews.** Melvin, 2026-11-17: "not sure if i'm allowed to stream multiple streams, i dont want to get banned". Providers cap connections per account, so Tuned never holds more than one stream: nothing plays two channels at once, nothing prefetches, previews or checks channels in the background. Every feature that opens a stream (auto-tune, backup copies, scans, downloads, recording) waits for or releases the one connection.
- **AirPlay.** Apple's protocol has no supported Android sender, and the reverse-engineered ones break on live streams (2026-11-17). Chromecast and DLNA cover TVs instead ([player](player.md)).
- **Backup files.** Melvin, 2026-11-18: "Remove the file back up feature". Saving the login, settings and favourites to a passphrase-locked file, and restoring from one, is removed; sync between devices through Google Drive covers moving to a new device ([sync](sync.md)). Don't add it back or suggest it.
- **A three-dot menu in the player, stream info and open in another player.** Melvin, 2026-11-19: "On the player I don't like the 3 dots. Get rid of stream info, get rid of open in another player. Make fit a button". The player's tools are buttons in its top row ([player](player.md)); don't put them back behind a menu or bring those two back.
- **IMDb ratings through OMDb.** Offered and then dropped (2026-11-17): ratings stay on TMDB, so one free key is all a person needs.

## Decided, being built

Each line names Melvin's words and date. Move it into the topic's file when it merges.

- **The app's side of the admin** (Melvin, 2026-11-19: usage stats "Yes, like Liftsmith"). The server and the admin are built ([admin](admin.md)); still to build in the app: anonymous usage stats from the allow-list, on by default outside the EU, EEA, UK and Switzerland, with Settings › Usage stats (the switch and the install id); reading `GET /api/flags` on open and acting on each switch; and Report a problem offering "Send to Tuned" beside the share sheet.

- **The web player** (Melvin, 2026-11-19: "ok lets do it"). The provider check is built ([web](web.md)); still to build: `:core` as Kotlin Multiplatform, the player at `/watch` for Premium accounts, and the rest of [web](web.md#what-was-decided).

Everything else that was listed has merged and is recorded in its topic: seats per account and extra seats in Play ([accounts](accounts.md)); never hiding sports and event channels ([channel scan](channel-scan.md)); Downloads by show with space left, offline mode and cache first ([offline](offline.md)); real episode names everywhere ([player](player.md)); actor pages ([people](people.md)); Live Events ([events](events.md)); the full grid guide ([playlist](playlist.md)); profiles with a PIN ([profiles](profiles.md)); phone and TV sync ([sync](sync.md)); Trakt ([Trakt](trakt.md)).

## Offered, not decided

- Skipping whole countries or languages at import to keep the database smaller (offered 2026-11-17 with the search speed work; the speed audit didn't need it).
