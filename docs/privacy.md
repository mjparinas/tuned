# Tuned privacy policy

Last updated: November 18, 2026

Tuned is an IPTV player for Android and Android TV. This policy explains what Tuned stores, what it sends and to whom. Tuned has no server of its own, no account with us, no ads and no analytics.

## What stays on your device

- **Your playlist logins** (server address, username and password, or playlist link) are stored encrypted with a key kept in the Android Keystore. They are deleted when you sign out.
- **Your playlists, guide, favourites, watch history, settings and downloaded films or episodes** are stored on the device only.
- **Crash reports and the app's log** stay on the device. Logins are removed from them before they are written. They leave the device only if you choose to copy or share them.
- Android's cloud backup is turned off for Tuned, so none of this reaches a backup.

## Who Tuned talks to

Tuned connects to these services only to do what you ask:

- **Your IPTV provider.** Tuned signs in to the server or link you entered, downloads its playlist and guide, and plays its streams. Your provider's own privacy policy applies to what it receives.
- **The Movie Database (TMDB)**, for posters, ratings, episode names and cast. Tuned sends titles and TMDB IDs, never your login.
- **OpenSubtitles**, only when you search for subtitles, and with your OpenSubtitles account only if you sign in to one.
- **Trakt**, only if you sign in to Trakt, to record what you watch to your Trakt account.
- **YouTube**, only when you open a trailer.
- **GitHub**, in the version downloaded from GitHub, to check for and download updates.
- **Google Play**, in the version from Google Play, for purchases. Google handles payment, and Tuned never sees your payment details.

## Sync with Google Drive

Sync is optional and off until you turn it on. When you sign in with Google, Tuned asks only for access to a hidden app folder in your Google Drive (the `drive.appdata` scope). It can't see or change any of your other Drive files.

Before anything leaves the device, Tuned locks your synced playlists, settings, favourites and watch later with a passphrase you choose (AES-256-GCM). Google stores only the locked file and can't read it. Tuned never sees your Google password. Stop syncing signs the device out and asks Google to revoke Tuned's access. You can also remove Tuned's access at any time from your Google account's security settings.

Tuned's use of information received from Google APIs follows the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including the Limited Use requirements.

## Sharing and selling

Tuned doesn't sell, rent or share your information. Nothing is used for advertising.

## Deleting your data

Sign out in Settings to delete your logins, playlists and guide from the device. Uninstalling Tuned deletes everything it stored on the device. Stop syncing, then remove Tuned's access in your Google account, to remove the synced copy's access.

## Children

Tuned isn't directed at children under 13 and doesn't knowingly collect their information.

## Changes and contact

If this policy changes, the new version will be posted here with a new date. Questions can be raised on [GitHub](https://github.com/mjparinas/tuned/issues).
