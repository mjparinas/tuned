# Live events

Melvin, 2026-11-17, from the app audit: a list of today's games, fights and pay-per-views, so a live event is one tap away instead of a scroll through dozens of "PPV 1 … PPV 50" channels. Each event has a Tune button, and you can follow teams and leagues to get alerts.

## How it works

- **Built from the guide** (`core/Events.kt`, with tests). The guide covers now until 36 hours ahead. A programme counts as an event when:
  - its channel's name or category marks it as sport or pay-per-view (Sports, PPV, ESPN, TSN, DAZN, UFC and so on);
  - its title names a match ("Leafs vs. Canucks", "Arsenal v Chelsea", "Lakers @ Celtics") or an event ("UFC 312", "Grand Prix", "Final");
  - it isn't filler ("Highlights", "Countdown", "SportsCenter", "Paid Programming").
- **One row per event.** The same title on several channels, starting within 30 minutes of each other, is one event, and the row says how many channels show it. A replay hours later is a separate row.
- **Tune** hands the title to [auto-tune](auto-tune.md). Auto-tune finds a working English stream for it, one connection at a time. Tune shows when the event is live or starts within the hour. Before that, the row has a bell instead.
- **Follow** a team, league or fighter by typing it ("Canucks", "UFC", "Premier League"). It has to match a whole word, so "Can" doesn't match "Canucks". Events you follow come first. After each guide refresh, an alert is set before each followed event, using the guide's reminder lead time (Settings › Guide). Tapping the alert auto-tunes that event, even when another alert is on the same channel. Unfollowing takes away the alerts it set, except those another followed name still covers. Alerts are ordinary guide reminders (WorkManager jobs), so they survive restarts.
- Hidden countries and groups are left out. "No event" placeholders and channels a scan found dead stay in, because an empty slot can carry the next event once the guide lists one.

## Times

Melvin, 2026-11-18: "it's not clear what timezone these are in, it should be adjusted based on the users current timezone. Can we add timezone in our settings and have it default to the device", and "truncate the day of the week to just 3 letters". So:

- Times show in the zone from Settings › Guide › Time zone, which follows the phone unless a zone is picked. "Later today" and "Tomorrow" split at midnight in that zone.
- A line under the follow box says which zone ("Times in PST"). The guide grid's corner ("Today · PST") and a channel's guide day headers say it too.
- Events after today read "Wed 9:00 p.m.", with the weekday cut to three letters.
- Guide times are stored as moments, converted from each programme's own XMLTV offset (a time with none is UTC, as XMLTV says), then moved by any `tvg-shift` and guide fix. The zone setting only changes how they read.

## Where

The trophy button in the Live TV tab's top bar opens it.

## Not done

- Events taken from event-channel names alone ("PPV 7: UFC 312 10PM ET") when the guide is empty. These names don't follow any one format. Search and auto-tune already match them.
- Scores and results. They would need an outside sports data service.
