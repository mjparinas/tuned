# Channel scan

Melvin, 2026-11-17: "is it possible to scan through all the channels kinda like the old days of scanning for tv signals and hiding anything automatically that isn't working, need to differentiate between the server is down, versus the channel is down versus there are current internet issues. we want to avoid flagging channels as down incorrectly."

## What it does

Settings › Channel health › **Scan channels** looks at every live channel that isn't already hidden by country or group, one at a time, in list order. Each look opens the stream, reads the first 16 KB (or, for an HLS playlist, the first segment or variant it points at) within 10 seconds, and closes it. Channels are 0.75 s apart, so a scan never hammers the server. It runs as a foreground job with a progress notification and can be stopped at any time. A scan of a few thousand channels takes a while, which is why it only runs when started by hand.

## Not blaming channels wrongly

`core/ChannelScan` and `core/ScanEngine` hold the rules, with tests.

- **What counts as a failure.** Only a clear answer: an HTTP error, nothing at all, a text page where video should be, no answer in time, or a refused connection. Anything binary counts as working. 401, 403, 429 and similar speak about the account, not the channel, and are checked against the server like any other failure.
- **The internet.** Before the scan, and after any failure, Android's own check of the network (a validated connection) must pass. If it doesn't, the scan waits up to five minutes and tries the same channel again, then stops and says the internet was down. No channel is blamed.
- **The server.** Before the scan, and after a long run of failures, Xtream's account call (`player_api.php`, a small answer) must come back with the login accepted and a free connection (`active_cons` below `max_connections`). If someone is watching on another device and the account is full, the scan waits and then stops with "your account was using all its connections". A plain M3U login has no such call, so the next rule carries the weight.
- **Other channels.** A failure only counts once another channel plays after it: usually the next one in the list. After a long run of failures (20 s with nothing playing), the last channel that played is looked at again. If it fails too, the streams stopped rather than the channels: the waiting failures are dropped unjudged, and after three tries a minute apart the scan stops and says the server wasn't sending streams. A channel that fails before anything has played waits for a second look at the end of the scan.
- **Two separate scans.** A channel is hidden only after failing in two scans at least six hours apart. Failing twice in one scan, or in two scans an hour apart, counts once. A scan that finds a hidden channel working brings it back at once.

## The report

The same screen shows the last scan's counts (working, failed, not judged) and, when it stopped early, why. Below are the hidden channels, each with **Show again**, and the channels that failed but are still shown. A channel shown again by hand is never hidden by a scan after that. **Hide channels that keep failing** (on by default) turns the hiding off without forgetting anything. Hidden channels stay out of every list, search, channel flipping and auto-tune, through the same hidden keys as countries and groups (`scan:dead`).

## One connection at a time

The scan never looks at a channel while the player is open or a download runs. A look in progress is cut off the moment either starts, and the scan carries on after. The account call is not a stream. What scans find is stored in `channel_scan` (Room v9). Switching to a different playlist clears it, and signing out stops a running scan.

## Empty event channels

Melvin, 2026-11-17: "Can you hide all these no event streaming links somehow". Providers keep hundreds of pay-per-view and event slots in the playlist between events, named like "- NO EVENT STREAMING - | 8K EXCLUSIVE | SE: PLAY+ PPV 43". A switch in Channel health, **Hide empty event channels** (on by default), leaves out live channels whose name says "no event" or "no live event" (`core/Placeholders.kt`, the same rule in SQL in `ItemDao`) from Live TV, search, auto-tune and the scan. It goes by the name alone, so a slot shows again as soon as a playlist refresh brings it under a real event's name. No scan is needed and nothing is stored.
