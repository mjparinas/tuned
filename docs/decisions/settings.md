# Settings

## Pages instead of one long list (Melvin, 2026-11-17)

Melvin: "go through settings and figure out how to improve and reduce the complexity. maybe group related settings together, have different screens for the different settings, merge some settings, whatever you think".

The first Settings screen is a short list of pages, each with an icon and a line saying what's inside, so a person finds a setting by its area instead of scrolling past sixty rows:

- **Playlists and account**: playlists, the account's status, backup and restore, Trakt and sync between devices (each only in builds with its keys), sign out.
- **Profiles and parental controls**: profiles, adult categories and the parental PIN.
- **Channels and lists**: start screen, starting on the last channel, the guide in the list, the grid guide, channel numbers, always showing the scroll bar, favourite order, and what's hidden (countries, groups, 24/7 channels).
- **Guide and refresh**: refresh now, when the playlist and guide download, pull to refresh, Wi-Fi only, guide days, guide fixes and reminders.
- **Playback**: audio language, resume, autoplay, skip intro, live rewind, buffering, then the screen and controls (landscape, picture-in-picture, frame rate, gestures, how long controls stay, skip lengths), clearing history, and Subtitles (finding subtitles by itself and the optional OpenSubtitles account), shown only in builds with the OpenSubtitles key.
- **Captions**: opens the caption screen directly, since it is already a page.
- **When a channel fails**: auto-reconnect, backup copies, channel health, and auto-tune's wait and quality. These were spread across Playback, Content and Auto-tune; they answer one question.
- **Appearance**: theme, dynamic colour, accent, pure black.
- **Downloads**: opens the downloads page directly.
- **Updates and advanced**: update checks, the user agent and Logs (developer mode was removed on 2026-11-18; see [diagnostics](diagnostics.md)). (The TMDB key moved out of Settings into a build secret on 2026-11-18; see [player](player.md).)

No setting was removed or renamed, so nobody loses a choice they made. Settings that looked mergeable (seek back and forward, playlist and guide refresh) have different defaults and choices, so they stay separate.

## How it's built

Each page is its own file in `ui/settings/` (`SettingsPlayback.kt` and so on), listed in the `SettingsPage` enum, and opened with `SettingsPageRoute`. A new setting goes in its page's file; a new page is an enum entry, a branch in `SettingsPageContent` and a file. Rows that open another screen pass its route to `onOpen`. Stored settings are declared once each in `data/SettingFields.kt`.
