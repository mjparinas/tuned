# Web player

Melvin, 2026-11-19: "is it possible to stream through a web ui, that could be a feature that is unlocked with pro", and the hard rule: **"i dont want any relaying"**. The plan is `tuned/tuned-web-player-plan.md` in the project files. He approved it the same day ("ok lets do it", then "try phase 0, put it behind a feature flag", yes to decisions 2 to 6).

## What was decided

- **No relaying, ever.** Streams, playlists and guides go straight from the provider to the browser. Tuned's server only signs people in, checks Premium and hands over the account's backup. Never propose a stream relay or proxy through our servers.
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

- The person types an Xtream login or an M3U link. The page tries the https twin of the address first (the provider's `https_port` when it names one), then the address itself, and tells "the browser blocked http", "no CORS" and "no answer" apart with a second, opaque request. Then it reads the first group of channels and films.
- **Try one channel and one film:** `.m3u8` through hls.js, `.ts` through mpegts.js, a film through the browser's own player, each closed before the next. It names the codecs, warns when the sound is one the browser can't play, and times the first picture.
- One answer in plain words (`verdict.mjs`, tested in `devices/lib/check.test.mjs`): works, works after one setting (with how to allow insecure content), not in Safari, the provider doesn't allow web players, this browser can't play the format, MAC portals need the app, wrong login, or no answer.
- **Private:** everything runs in the browser; the address and login are never sent to Tuned, never stored, and the shareable summary leaves out the address and password. The page has its own CSP: scripts only from our site (the two players are vendored in `check/vendor/`), connections and media to any host.
