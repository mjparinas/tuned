# Tuned privacy policy

Last updated: November 22, 2026

Tuned is an IPTV player for Android and Android TV. This policy explains what Tuned stores, what it sends and to whom. A Tuned account is optional. Tuned has no ads and no third-party analytics; its own usage stats are described below.

## What stays on your device

- **Your playlist logins** (server address, username and password, or playlist link) are stored encrypted with a key kept in the Android Keystore. They are deleted when you sign out.
- **Your playlists, guide, favourites, watch history, settings and downloaded films or episodes** are stored on the device only.
- **Crash reports and the app's log** stay on the device. Logins are removed from them before they are written. They leave the device only if you choose to copy or share them, or to send them to Tuned from Report a problem.
- Android's cloud backup is turned off for Tuned, so none of this reaches a backup.

## Who Tuned talks to

Tuned connects to these services only to do what you ask:

- **Your IPTV provider.** Tuned signs in to the server or link you entered, downloads its playlist and guide, and plays its streams. Your provider's own privacy policy applies to what it receives.
- **The Movie Database (TMDB)**, for posters, ratings, episode names and cast. Tuned sends titles and TMDB IDs, never your login.
- **OpenSubtitles**, only when you search for subtitles, and with your OpenSubtitles account only if you sign in to one.
- **Trakt**, only if you sign in to Trakt, to record what you watch to your Trakt account.
- **YouTube**, only when you open a trailer.
- **The host of Tuned's download**, in the version from www.tunedplayer.com, to check for and download updates.
- **Google Play**, in the version from Google Play, for purchases. Google handles payment, and Tuned never sees your payment details.

## Signing in on a TV with your phone

When you sign in on a TV with your phone, the TV makes a one-time key and shows it in a QR code. Your phone (the Tuned app, or the page at www.tunedplayer.com/tv) locks what it sends with that key: your playlist login, and from the app also your settings, favourites, lists and watch later. Tuned's sign-in service only passes the locked copy to the TV. It can't unlock it, and it deletes it as soon as the TV takes it, or after 10 minutes. It keeps no record of what was sent; to limit misuse it counts requests per network address, keeping the address only scrambled and only for an hour.

## Your Tuned account

An account is optional: watching never needs one. If you sign in with Google on the Tuned website or in the app, Tuned's service stores your email address and name from Google, the devices you sign in (the name Android gives them, such as "Pixel 8", the kind of device, a scrambled device ID and when each was last used), and what you bought. Payments on the website are handled by Stripe, and Tuned never sees your card.

If you turn on backup, the service keeps your saved playlists (including their logins), settings, profiles, favourites, lists and watch later, so a new device can restore them. The backup isn't encrypted by Tuned. It is reachable only by devices signed in to your account, and the last 5 versions are kept. While you're signed in, the app also keeps a copy of the live channel list (with each channel's country) and the next day of guide of the playlist it is using in your account, without the playlist's password, so the website's Channels and guide page and its Watch page can show them and a new device can start from them; it goes with the backup when you delete it. When the website shows a channel logo that your provider keeps on an insecure (http) address, Tuned's service fetches that picture for your browser; it fetches pictures only and keeps none of them. Streams never pass through Tuned's service: Watch plays them straight from your provider in your browser, and only one browser on your account plays at a time.

You can remove a device, delete your backup, or delete your whole account on the account page at any time. Deleting the account deletes all of this and cancels a yearly plan bought on the website.

## The website's visitor count

Tuned's website (the account, TV sign-in and Premium devices pages) counts visits itself, without cookies or anything stored in your browser. Each visit sends the page, the site that linked to it, any campaign tags in the link, and which button you pressed, if any. The server also notes your country from our host's location header, and your browser, system and kind of device from the browser's own description. To count each visitor once a day, it combines your network address and browser with the date and a secret, and keeps only that scrambled result, never the address; the next day gives a different result, so visits can't be linked across days. Nothing is counted when your browser sends Global Privacy Control. These records are deleted after 25 months.

## Usage stats from the app

To learn what to fix and build, the app sends Tuned's own service anonymous usage stats: that the app was opened, that a playlist was added (only its kind, such as Xtream or link), that something played or failed to play (only the section, such as Live TV, whether it was downloaded or cast, and why it failed in a word), that a backup was saved or restored, crashes and freezes (only that one happened), and that an update was installed. With them go a random install id the app makes (not Android's advertising id), the app's version and build, the Android version, the device's maker, model and kind (phone, tablet or TV), its language and time zone; the service notes the country from our host's location header, and keeps the network address only scrambled and only for an hour, to limit misuse. They never include what you watch or search, channel or title names, playlist addresses, logins, or anything you type. They go up at most about once an hour, and are deleted after 25 months.

They are on by default outside the EU, the EEA, the UK and Switzerland, and off by default inside them (or when the device gives no sign of where it is) until you switch them on. Switch them off in Settings › Updates and advanced › Privacy at any time: that deletes what hasn't been sent and the install id. Signing out makes a new install id.

The app also reads a short list of switches from Tuned's service when it opens, so a feature that leans on another service can be paused without an update. It sends the install id with that only while usage stats are on.

## Problem reports

Settings › Updates and advanced › Report a problem can send Tuned what you write, an email address if you give one for a reply, the latest crash or freeze report, the recent log (with logins removed) and the device's details. It is sent only when you press Send to Tuned, and Tuned keeps it to work on the problem. Share instead sends it nowhere but where you choose.

## Sharing and selling

Tuned doesn't sell, rent or share your information. Nothing is used for advertising.

## Deleting your data

Sign out in Settings to delete your logins, playlists and guide from the device. Uninstalling Tuned deletes everything it stored on the device. Delete your backup or your whole account on the account page to remove them from Tuned's service.

## Children

Tuned isn't directed at children under 13 and doesn't knowingly collect their information.

## Changes and contact

If this policy changes, the new version will be posted at www.tunedplayer.com/privacy with a new date. Questions can be sent from **Settings › Updates and advanced › Report a problem** in the app.
