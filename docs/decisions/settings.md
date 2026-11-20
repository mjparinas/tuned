# Settings

## Pages instead of one long list (Melvin, 2026-11-17)

Melvin: "go through settings and figure out how to improve and reduce the complexity. maybe group related settings together, have different screens for the different settings, merge some settings, whatever you think".

The first Settings screen is a short list of pages, each with an icon and a line saying what's inside, so a person finds a setting by its area instead of scrolling past sixty rows:

- **Playlists and account**: playlists, the account's status, Trakt (only in builds with its key), the Tuned account and its backup ([accounts](accounts.md)), sign out.
- **Profiles and parental controls**: profiles, adult categories and the parental PIN.
- **Channels and lists**: start screen, starting on the last channel, the guide in the list, the grid guide, channel numbers, always showing the scroll bar, favourite order, and what's hidden (countries, groups, 24/7 channels).
- **Guide and refresh**: refresh now, when the playlist and guide download, pull to refresh, Wi-Fi only, instant playlist switching ([onboarding](onboarding.md)), guide days, guide fixes, the time zone guide and event times show in (the phone's own by default; Melvin, 2026-11-18), reminders, and Notifications, which opens Android's page for Tuned's notifications and the dot on the app icon ([playlist](playlist.md)).
- **Playback**: audio language, resume, autoplay, skip intro, live rewind, buffering, then the screen and controls (landscape, picture-in-picture, background play, frame rate, sound delay, tunnelled playback (TV only), gestures, how long controls stay, skip lengths), clearing history, and Subtitles (finding subtitles by itself and the optional OpenSubtitles account), shown only in builds with the OpenSubtitles key.
- **Captions**: opens the caption screen directly, since it is already a page.
- **When a channel fails**: auto-reconnect, backup copies, channel health, and auto-tune's wait and quality. These were spread across Playback, Content and Auto-tune; they answer one question.
- **Appearance**: theme, dynamic colour, accent, pure black, lighter mode ([speed](speed.md)).
- **Downloads** is not in Settings (Melvin, 2026-11-18: "settings doesn't need downloads since it already has it on the home screen"). It is in the home screen's bar; Wi-Fi only for downloads stays under Guide and refresh.
- **Updates and advanced**: update checks, the user agent (a picklist of common players, or your own), Privacy (Send anonymous usage stats, with the install id; [admin](admin.md)), Report a problem and Logs (developer mode was removed on 2026-11-18; see [diagnostics](diagnostics.md)). (The TMDB key moved out of Settings into a build secret on 2026-11-18; see [player](player.md).)

No setting was removed or renamed, so nobody loses a choice they made. Settings that looked mergeable (seek back and forward, playlist and guide refresh) have different defaults and choices, so they stay separate.

**Search** (Melvin, 2026-11-18: "build all three", from the UI review). A search box sits at the top of Settings' first screen. Typing shows every matching setting from every page under its page's name, matched by every typed word against the row's title and what it currently says, ignoring case and accents. The results are the pages' own rows, so a switch flips right there and a choice opens its dialog. A page whose name matches is listed as a row that opens it, and so is Captions. Each row widget (`SettingRow`, `SwitchRow`, `ChoiceRow`, `SliderRow`, `ColourRow`) checks the query itself (`SettingsSearch.kt`), so a new setting is searchable with no list to keep up to date. A custom row that isn't built on those widgets calls `searchHides` itself, as the accent colours row does.

**User agent picklist** (Melvin, 2026-11-19: "for user agent, can you create a picklist of the most common user agents that these iptv providers, stbs use, and then have an option to use a custom one please. it should default to tuned"). Some servers only answer players they know, so Updates and advanced › User agent opens a list instead of a text box: Tuned (default), VLC, TiviMate, IPTV Smarters, OTT Navigator, Kodi, MAG box (Stalker portal boxes), ExoPlayer and Chrome, each with the string it sends shown under its name, then **Custom…**, which opens the text box. The strings live in `core/UserAgents.kt`. The setting still stores the string itself (`user_agent`, blank for Tuned's own `Tuned/<version>`), so nothing was migrated: a stored value that matches a preset shows as that preset, and anything typed before shows as "Custom: …", unchanged. A stream's own `#EXTVLCOPT` user agent still wins over this ([playlist](playlist.md)). When a player's version moves on, update its string here; servers match on the name, not the exact version.

## The look: headed cards and coloured badges (Melvin, 2026-11-19)

Melvin, sending screenshots of Horizon Maps' settings: "Is there a way to style the settings nicely like in the screenshots". So:

- **First screen.** The search box, then three headed sections, each one rounded card: **Account** (Playlists and account, Profiles and parental controls), **Watching** (Channels and lists, Guide and refresh, Playback, Captions, When a channel fails) and **App** (Appearance, Updates and advanced). Each page's row has a round tinted badge with its icon, its name, and a line saying what's inside; the badge colours follow the section (blue, violet and teal, red for failures, amber). The last card, **Tuned**, holds the version, the build's commit and a **What's new** button ([updates](updates.md)).
- **Pages.** Section headers are small capitals over the card they head, and every run of rows between two headers sits on one rounded card. The pages are still flat lists of rows: `SettingsGroups` (`ui/settings/SettingsGroups.kt`) finds the runs between headers and draws a card behind each, so a new row joins its section's card with no extra code. Anything that should sit between cards rather than on one (the search box, a note) uses `Modifier.outsideCards()`.
- **Contrast.** Each badge colour has a deep shade for light themes and a soft one for dark themes, so the icon stays clear of its tinted circle; the text keeps the theme's own colours. Headers stay headings for screen readers, which read them as written, not spelled out.
- Horizon Maps is GPL-3.0, so only the look was taken, never its code: copying it would make Tuned, including the paid Play build, GPL too.

## How it's built

Each page is its own file in `ui/settings/` (`SettingsPlayback.kt` and so on), listed in the `SettingsPage` enum, and opened with `SettingsPageRoute`. A new setting goes in its page's file; a new page is an enum entry, a branch in `SettingsPageContent` and a file. Rows that open another screen pass its route to `onOpen`. Stored settings are declared once each in `data/SettingFields.kt`.
