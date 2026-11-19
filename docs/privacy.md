# Tuned privacy policy

Last updated: November 19, 2026

Tuned is an IPTV player for Android and Android TV. This policy explains what Tuned stores, what it sends and to whom. A Tuned account is optional. Tuned has no ads and no analytics.

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

## Signing in on a TV with your phone

When you sign in on a TV with your phone, the TV makes a one-time key and shows it in a QR code. Your phone (the Tuned app, or the page at tuned-devices.vercel.app/tv) locks what it sends with that key: your playlist login, and from the app also your settings, favourites, lists and watch later. Tuned's sign-in service only passes the locked copy to the TV. It can't unlock it, and it deletes it as soon as the TV takes it, or after 10 minutes. It keeps no record of what was sent; to limit misuse it counts requests per network address, keeping the address only scrambled and only for an hour.

## Sync with Google Drive

Sync is optional and off until you turn it on. When you sign in with Google, Tuned asks only for access to files it creates itself (the `drive.file` scope). It keeps one file, called "Tuned sync", in your Drive, and it can't see or change any of your other Drive files.

The file holds your saved playlists (including their logins), settings, favourites and watch later, so your phone and TV stay the same. It isn't encrypted by Tuned: it is protected by your Google account, so anyone who can open your Drive could read it. Deleting the file removes the synced copy. Tuned never sees your Google password. Stop syncing signs the device out and asks Google to revoke Tuned's access. You can also remove Tuned's access at any time from your Google account's security settings.

Tuned's use of information received from Google APIs follows the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including the Limited Use requirements.

## Your Tuned account

An account is optional: watching never needs one. If you sign in with Google on the Tuned website or in the app, Tuned's service stores your email address and name from Google, the devices you sign in (the name Android gives them, such as "Pixel 8", the kind of device, a scrambled device ID and when each was last used), and what you bought. Payments on the website are handled by Stripe, and Tuned never sees your card.

If you turn on backup, the service keeps your saved playlists (including their logins), settings, profiles, favourites, lists and watch later, so a new device can restore them. The backup isn't encrypted by Tuned. It is reachable only by devices signed in to your account, and the last 5 versions are kept.

You can remove a device, delete your backup, or delete your whole account on the account page at any time. Deleting the account deletes all of this and cancels a yearly plan bought on the website.

## Sharing and selling

Tuned doesn't sell, rent or share your information. Nothing is used for advertising.

## Deleting your data

Sign out in Settings to delete your logins, playlists and guide from the device. Uninstalling Tuned deletes everything it stored on the device. Stop syncing, then remove Tuned's access in your Google account, to remove the synced copy's access.

## Children

Tuned isn't directed at children under 13 and doesn't knowingly collect their information.

## Changes and contact

If this policy changes, the new version will be posted here with a new date. Questions can be raised on [GitHub](https://github.com/mjparinas/tuned/issues).
