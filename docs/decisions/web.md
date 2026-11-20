# Web player

Melvin, 2026-11-19: "is it possible to stream through a web ui, that could be a feature that is unlocked with pro", and the hard rule: **"i dont want any relaying"**. The plan is `tuned/tuned-web-player-plan.md` in the project files. He approved it the same day ("ok lets do it", then "try phase 0, put it behind a feature flag", yes to decisions 2 to 6).

## Status: paused

Melvin ran the provider check on his own provider the evening of 2026-11-19: "provider blocks web players" (no CORS). Phase 0 was the go or no-go, so the web player itself isn't being built. The check stays at `/check`, off by default behind `web_player`, and everything below records what was decided for when it comes back, most likely with the local helper.

## What was decided

- **No relaying, ever.** Streams, playlists and guides go straight from the provider to the browser. Tuned's server only signs people in, checks Premium and hands over the account's backup. Never propose a stream relay or proxy through our servers. Melvin clarified the same day: "no relaying through tuned servers". A helper on the person's own computer that relays only when needed (as Stremio's local streaming server does at `127.0.0.1:11470`) fits that rule, but "i don't want to support relaying right now. that's a future feature", so it isn't built or planned yet.
- **What that means.** A browser on an https page blocks `http://` streams unless the person allows insecure content for the site (Chrome, Edge, Firefox; Safari has no such setting), and it can only read a provider that answers with CORS. Providers without CORS, MAC portals (a page can't set `Cookie` or `User-Agent`) and a custom User-Agent can't work on the web. The page says so plainly and points to the app.
- **Seats:** the web player doesn't take one of the 3 device seats, but only one browser per account plays at a time (a short lease; a second browser takes over and the first stops).
- **Who gets it:** Premium bought in Play or on the website. The `github` build's everything-unlocked app doesn't count, since the server has no purchase on record.
- **The provider check is free and needs no account,** so nobody pays for something their provider blocks.
- **v1 only reads the backup.** Favourites and progress changed on the web stay in that browser; writing back to the account comes later.
- **Later:** a phone pauses its downloads while the same account watches on the web, so the one-connection rule holds across devices.
- **One stream at a time** in the page: the old loader is destroyed before the next opens, nothing prefetches, one tab plays.
- Code is shared with the parked Samsung TV plan (KMP `:core` compiled to JavaScript, the same web UI), in a `web/` folder.

## Built: the provider check (Phase 0)

`/check` on the website (`devices/public/check/`), behind the admin flag **`web_player`** (off by default; [admin](admin.md)). Preview deployments and localhost always show it, so it can be tried before a release.

- The person types an Xtream login or an M3U link. Switching to Playlist link fills in the login's M3U link (`get.php`, as the app builds it), and a pasted `get.php` link fills empty Xtream fields the other way (Melvin, 2026-11-19). The page tries the https twin of the address first (the provider's `https_port` when it names one), then the address itself, and tells "the browser blocked http", "no CORS" and "no answer" apart with a second, opaque request. Then it reads the first group of channels and films.
- **Try one channel and one film:** `.m3u8` through hls.js, `.ts` through mpegts.js, a film through the browser's own player, each closed before the next. It names the codecs, warns when the sound is one the browser can't play, and times the first picture.
- One answer in plain words (`verdict.mjs`, tested in `devices/lib/check.test.mjs`): works, works after one setting (with how to allow insecure content), not in Safari, the provider doesn't allow web players, this browser can't play the format, MAC portals need the app, wrong login, or no answer.
- **Private:** everything runs in the browser; the address and login are never sent to Tuned, never stored, and the shareable summary leaves out the address and password. The page has its own CSP: scripts only from our site (the two players are vendored in `check/vendor/`), connections and media to any host.

### Your saved playlists (Melvin, 2026-11-20)

"if you're logged in, /check should just let you choose the playlist to try", and "check should show up in the account page somewhere".

- **Signed in, `/check` opens on Your playlists:** the account's playlists from the backup (the same list as `/account/playlists`), one picked, and Check. MAC portals and files are listed but can't be picked (a browser can't use them). Typing an address stays, behind "Type an address instead". Signed out, the form shows with a Sign in link that comes back to `/check` (`/api/auth/google?then=check`).
- **The login comes to the browser only for the check:** `GET /api/account/check?id=<playlist id>` (`devices/api/account/check.mjs`, `checkLogin` in `lib/library.mjs`, tested) answers one Xtream login or M3U link (with its guide links, which the web channel list uses too), only to the person's own signed-in browser, only for a fetch from our own page (`Sec-Fetch-Site: same-origin`), only while `web_player` is on (preview deployments always), at most 60 an hour. It is kept in memory like a typed login; the check still talks to the provider from the browser and Tuned's server never contacts the provider. The Playlists page itself still never gets passwords or links.
- **On the account pages:** "Check my provider" in the side menu, and a Check button on each Xtream or link playlist on `/account/playlists` (it opens `/check?playlist=<id>` and starts the check). Both only while `web_player` is on (`flagOn` in `public/account/nav.js`).

### Try playing anyway (Melvin, 2026-11-20)

"a playlist might fail a check, can we try a webstream anyways?" After a check that couldn't read the lists (the provider doesn't allow web players, no answer, or http blocked), the answer offers **Try playing anyway**: a channel's number (for an Xtream login) or a stream link, played by the browser's own player first (a `<video>` needs no CORS, so it can sometimes play what the check couldn't read), then hls.js or mpegts.js. One stream at a time, each closed before the next, straight from the provider with no relay. It says plainly which ways failed and why, or, when one plays, "A channel plays, but the lists don't": the web player needs the lists, so the app is still the way to watch (`anywayTries` and `canTryAnyway` in `verdict.mjs`, tested).

## Built: the channel list (behind the flag)

Melvin, 2026-11-20, after his provider failed the check: "or show me all the channels and have a link that allows me to open up vlc or some other player? include an epg. this should be on a different screen somewhere". So `/account/channels` (`devices/public/account/channels/`), in the account's side menu as **Channels and guide**, behind the same **`web_player`** flag (preview deployments and localhost always show it).

- **Pick a saved playlist** (`?playlist=<id>` opens one; the provider check links here). The browser gets that playlist's login from `/api/account/check`, the same endpoint the check uses, then reads the live channels (Xtream `get_live_categories` and `get_live_streams`, or the M3U link) and the guide (`xmltv.php`, the playlist's own guide link, or the M3U header's) **straight from the provider**, https twin first. The guide streams in as it downloads and keeps only the next 12 hours of the playlist's channels (`core.mjs` `GuideReader`, tested in `devices/lib/channels.test.mjs`), so a 100 MB guide never sits in memory.
- **Two views:** now and next (with how far through, search by channel or what's on, a group filter) and a three-hour guide grid.
- **Each channel opens in another player on the person's device,** which connects to the provider itself: Open in VLC (an intent for VLC on Android, VLC's x-callback link on iPhone and iPad, `vlc://` on a Mac), a one-channel `.m3u` file (Play on Windows and Linux, where VLC has no link, opening in VLC or the usual player), and Copy link. The page never plays or fetches a stream and never prefetches, so the one-connection rule is the player's, as in the app. Links aren't shown on the page, since they carry the login.
- **The whole playlist** always gets Open in VLC, a playlist file for VLC (one entry, the provider's own playlist link, which VLC expands) and Copy playlist link. VLC isn't a web page, so this works **even for providers that block web pages**, like Melvin's.
- **When the provider blocks web pages** (no CORS, or http that the browser blocks), the page says which in plain words, how to allow insecure content where that's the cause, and points to the whole-playlist links and the app. No relay, as decided above.
- MAC portals and files on a device say they're app only. Nothing is cached in the browser yet (the login would be kept with it); the list loads fresh each time.

### The app's copy (Melvin, 2026-11-20)

Asked whether the app should share its channel list so the page works with providers that block web pages, Melvin chose **"Yes, the app shares its list"**.

- **The app** (`data/SharedChannels.kt`, the format in core `ChannelShare`, tested) sends the playlist in use's live channels and the next 12 hours of its guide (titles only, from an hour ago) to `PUT /api/account/channels`, gzipped, with the hourly backup run (`CloudSyncWorker`): when the channel list changed, and at least every 6 hours so the guide stays ahead. Every signed-in app does this, whatever the `web_player` flag (Melvin, 2026-11-20, on a card in "Fix TV stuck on getting channels ready": the copy also lets a new or reset device fill Live TV when the provider download fails); only the website's page that reads it stays behind the flag. The guide is read 500 channels at a time and written straight into the gzipped copy; a copy over 4 MB is sent without its guide, and one still over is skipped and logged. MAC portals aren't shared (their links need a session VLC can't open). Links carry `{login}`, `{user}` and `{pass}` in place of the login, so the list holds no password; the page fills them in from `/api/account/check`.
- **The server** (`api/account/channels.mjs`, `lib/channel-lists.mjs`, tested) takes uploads from any signed-in device, flag or not, and keeps the newest copy per playlist, the 10 newest playlists per account, in `channel_lists` (`schema.sql`; also made on first use). It checks the copy's shape and unpacks at most 64 MB. Reading is `GET ?playlist=<id>` for the person's own signed-in browser, from our page, while `web_player` is on, sent gzipped as saved. Deleting the backup or the account deletes the copies too ([privacy](../privacy.md)).
- **The page** fetches the copy and the login together. A copy under a day old opens at once with its guide ("From your Tuned app on Pixel 8, 2 hours ago"), with no request to the provider. Otherwise it asks the provider first and falls back to the copy, however old, when the provider blocks web pages. A file playlist now shows its channels too, from the copy.
- Still no relay: only the list goes through Tuned; every stream goes from the provider to VLC on the person's device.
- No setting in the app for now: it only happens behind the admin flag, and the copy is useful only with the page. Add one ("Share the channel list with the website") if Melvin wants it either way.
