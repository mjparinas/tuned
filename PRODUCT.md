# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Primary users watch on Android TV in the living room with a remote: they have already signed in once with their own playlist (Xtream Codes, M3U link or file, MAC portal, or a home media server such as Plex, Jellyfin or Emby) and want to browse Live TV, movies and shows and start watching quickly and effortlessly.

Many users also use the phone and tablet app — browsing, casting, or as a remote while a TV plays. Secondary audiences to keep in mind: kids profiles (Premium) and people following live sports and events from the guide.

## Product Purpose

Tuned is a native Android IPTV player. Sign in to your TV service once; Live TV, Movies and Shows are there from the device cache at once, with a guide, captions, audio choice and picture-in-picture. The app refreshes in the background and stays out of the way while video plays.

Success means someone can set up once, then browse to what they want to watch quickly and effortlessly — the UI gets out of the way and stays super performant.

## Positioning

Speed first and a quiet UI that gets out of your way: fully native Media3 (not a WebView shell), lists from Room with nothing waiting on the network at open, and exactly one stream connection at a time so providers do not ban the account. Provider-agnostic player only — Tuned includes no channels; the person brings the playlist. No ads.

## Operating Context

- Living-room Android TV with a D-pad remote (overscan-safe layouts, focus rings, TV-dark system theme).
- Phones and tablets: browse, play, cast; phone can act as remote while a TV plays.
- Companion website (tunedplayer.com): landing and blog, Tuned account (Google sign-in, seats, Stripe, backups), channel list/guide in the browser, Premium web player behind a flag, TV phone sign-in relay.
- Playlists refresh and downloads wait while anything is watching; watching always wins the one connection.
- Canadian English for user-facing English; app follows the device language across 11 translations.

## Capabilities and Constraints

- Playlist kinds: Xtream Codes, M3U/M3U8 link or file (+ optional guide), MAC (Stalker) portals, Plex / Jellyfin / Emby.
- Live TV (grid guide, filters, flip), Movies and Shows (posters, TMDB details, real episode names), Favourites and custom lists, Continue watching, offline downloads, casting, picture-in-picture / mini player, profiles (incl. kids + PIN on Premium), Trakt and Simkl, Live Events, channel scan, auto-tune.
- Two app builds: `github` (self-updater, everything unlocked) and `play` (Play Billing for Premium). Store builds never name a provider, channel or sports brand.
- One stream connection ever; no multi-view, live preview, second-channel inset, sleep timer, or AirPlay. No fabricating channels or programmes.
- Stack is established: Kotlin, Jetpack Compose (Material 3), Navigation Compose, Room + Paging 3, WorkManager, DataStore, Media3 ExoPlayer, OkHttp, Coil 3; website is plain Node on Vercel. Not a greenfield stack choice.
- Binding product and engineering rules live in AGENTS.md and `docs/decisions/` — treat those as product authority alongside this file.
- Undecided / open: none raised in init beyond what decision docs already mark as paused or not decided (e.g. local web relay helper paused).

## Brand Commitments

- Name: **Tuned** (never translated); site: tunedplayer.com.
- Mark: the channel stack (three fanned play buttons — violet, teal, white — on Tuned ink); assets under `docs/branding/`.
- Colours: violet `#6D4AFF`, deep `#3B1FB8`, teal `#00C2A8`, ink `#121018` / splash ink `#1A1726`. Themes: light, dark, system; optional wallpaper colours and pure black.
- Voice: clean, direct, gets out of the way; Canadian English spelling (colour, favourite, behaviour, centre, cancelled; -ize).
- Website styled like the app from `devices/public/site.css`; admin keeps a denser ops hybrid (Focus Overview: Needs you first), not consumer-site chrome.

## Evidence on Hand

- Product and technical authority: README.md, AGENTS.md, `docs/decisions/` (per-topic decision records).
- Store listing copy: `docs/play-store/listing.txt`; privacy: `docs/privacy.md`.
- Brand assets: `docs/branding/` (SVG, PNG, preview).
- JVM screenshot fixtures for every screen: `app/src/test/kotlin/com/tunedplayer/app/screenshots/` (Roborazzi).
- Do not fabricate testimonials, customer names, benchmarks, channel line-ups, or provider/sports brand names that are not already in committed content.

## Product Principles

1. Get out of the way — show cache instantly; player is video-first; no pop-ups beyond what’s needed to add a first playlist.
2. Speed first — nothing heavy before the first frame; lists page from Room; background work waits until the first screen is up.
3. Waiting is never a mystery — real progress, named steps, and clear feedback for every refresh, download or sync.
4. One stream connection, ever — watching wins; downloads and scans wait.
5. Customizable and defensive — settings when people could reasonably want either way; catch and log failures so the app does not crash.

## Accessibility & Inclusion

Android: TalkBack, Switch Access, keyboard/remote, large text, and Android’s “Time to take action”; 48 dp targets; colour states also given in words; player controls stay up while a screen reader is on. Website: WCAG 2.2 AA, checked in CI (`devices/a11y-check.mjs`). Known gap: grid guide row height can clip times at the largest font sizes.
