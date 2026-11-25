# Live events

Melvin, 2026-11-17, from the app audit: a list of today's games, fights and pay-per-views, so a live event is one tap away instead of a scroll through dozens of "PPV 1 … PPV 50" channels. Each event has a Tune button, and you can follow teams and leagues to get alerts.

## How it works

- **Built from the guide** (`core/Events.kt`, with tests), and from channel names where the guide is empty (below). The guide covers now until 36 hours ahead. A programme counts as an event when:
  - its channel's name or category marks it as sport or pay-per-view (Sports, PPV, ESPN, TSN, DAZN, UFC and so on);
  - its title names a match ("Leafs vs. Canucks", "Arsenal v Chelsea", "Lakers @ Celtics") or an event ("UFC 312", "Grand Prix", "Final");
  - it isn't filler ("Highlights", "Countdown", "SportsCenter", "Paid Programming").
- **One row per event.** The same title on several channels, starting within 30 minutes of each other, is one event, and the row says how many channels show it. A replay hours later is a separate row.
- **Tune** hands the title to [auto-tune](auto-tune.md). Auto-tune finds a working English stream for it, one connection at a time. Tune shows when the event is live or starts within the hour. Before that, the row has a bell instead.
- **Follow** a team, league or fighter by typing it ("Canucks", "UFC", "Premier League"). It has to match a whole word, so "Can" doesn't match "Canucks". Events you follow come first. After each guide refresh, an alert is set before each followed event, using the guide's reminder lead time (Settings › Guide). Tapping the alert auto-tunes that event, even when another alert is on the same channel. Unfollowing takes away the alerts it set, except those another followed name still covers. Alerts are ordinary guide reminders (WorkManager jobs), so they survive restarts. On Android TV the type-to-follow box stays off (phone or website), and chips already followed can still be removed ([TV](tv.md)).
- **From channel names when the guide lists nothing** (Melvin, 2026-11-25: "go ahead and build out the 2 small items"; `core/ChannelEvents.kt`, with tests). Providers rename event slots before each event ("PPV 7: UFC 312 10PM ET", "US| NHL 05: Leafs vs Canucks @ 7:00 PM EST", "DAZN 3 | Canelo vs Crawford Sat 9PM ET") in no one format, so for each sports or pay-per-view channel whose guide has no event in the next 36 hours, Tuned:
  - takes the country ("US|") and slot label ("PPV 7:", "NHL 05 -") off the front, but keeps an event's own number ("UFC 312");
  - reads the time from anywhere in the name ("10PM", "7:00 p.m.", "19:30", "19h30"), with a date ("11/26", "2026-11-26", "Nov 26"; month first, as North American providers write it, unless the first number can't be a month), a weekday ("Sat") and a zone (ET, PT, CT, MT, AT, GMT, UTC, UK, BST, CET, AEST) beside it. A time with no zone is in Settings › Guide › Time zone. A time with no date is the next one that hasn't ended, so "1AM" read at noon is tonight's; a second time for another coast ("10PM ET / 7PM PT") comes off too;
  - keeps what is left only if the same rules as the guide's titles call it an event, and the name was written as "slot: event", gives a time, or names a match ("Lakers @ Celtics"). So "PPV 7", "NFL 01 -- No Event --", "Sky Sports Main Event" and "Sky Sports Golf Open" are no event.
  - An event from a name lasts 3 hours, since the name only says when it starts. It joins the guide's event of the same title starting within 30 minutes (or any one, when the name gives no time), so the row counts both channels.
  - A name with no time is listed under Later today with "No time" where the time would be, and Tune instead of a bell, since it may be on now. It never sets an alert.
  - A name the rules can't read (a date that doesn't exist, "2/30") is skipped, and anything else that goes wrong reading names is logged and leaves the guide's events as they were.
- Hidden countries and groups are left out. "No event" placeholders and channels a scan found dead stay in, because an empty slot can carry the next event once the guide lists one.

## Times

Melvin, 2026-11-18: "it's not clear what timezone these are in, it should be adjusted based on the users current timezone. Can we add timezone in our settings and have it default to the device", and "truncate the day of the week to just 3 letters". So:

- Times show in the zone from Settings › Guide › Time zone, which follows the phone unless a zone is picked. "Later today" and "Tomorrow" split at midnight in that zone.
- A line under the follow box says which zone ("Times in PST"). The guide grid's corner ("Today · PST") and a channel's guide day headers say it too.
- Events after today read "Wed 9:00 p.m.", with the weekday cut to three letters.
- Guide times are stored as moments, converted from each programme's own XMLTV offset (a time with none is UTC, as XMLTV says), then moved by any `tvg-shift` and guide fix. The zone setting only changes how they read.

## Where

The Live events chip, first in the Live TV tab's row of categories, opens it (it was a trophy button beside search until Melvin's UI review on 2026-11-18 moved it, to give the search box its width).

## Not done

- Scores and results. They would need an outside sports data service.
