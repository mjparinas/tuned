# Onboarding and playlists

**Several playlists, added one at a time** (Melvin, 2026-11-17: "support multiple playlist streams… adding the username and password login on the main page doesn't really make sense… make it easy for users to add those"). The first screen no longer asks for a server, username, password and two links at once.

- **Welcome** (first run only): the brand, one line ("Add a playlist from your TV provider to start watching"), and three cards for what providers hand out: **Xtream Codes login**, **Playlist link** (M3U or M3U8, with an optional guide link) and **Playlist file** (an `.m3u` on the device). Under them, **Restore from Google Drive** brings back the playlists, profiles and every setting from another device in one step (Melvin, 2026-11-19; [sync](sync.md)). There is nothing else to get through: no tour, no permissions.
- **One form per kind**, each with an optional name ("Sports", "Living room"). The Xtream form keeps the default server (`http://eros4k.ddns.net`) filled in and still checks the login with the server's small account answer before opening the app.
- **Pasted links are read for what they are** (`core/PlaylistLink`). A `get.php` or `player_api.php` link with a username and password becomes an Xtream login, so catch-up and account status work too; pasted into the Xtream server box, it fills in all three fields. Anything else that looks like a web address is a plain link, with `http://` added when missing.
- **Files are copied into the app** (`files/playlists/`, one file each), so the playlist still loads after the original is moved or deleted, and refreshes read it from there instead of the network. A file that doesn't start like an M3U playlist is refused. Files aren't synced to other devices, since they belong to this one.
- **The guide of a plain playlist** is the link given, else the one its `#EXTM3U` line names (`x-tvg-url`, `url-tvg` or `tvg-url`), read while the playlist is saved. Without either, there is no guide, rather than asking the default Xtream server for one.

**Settings › Playlists** is a full screen: every saved playlist with its name, kind and where it comes from (never the password), a check on the one in use, and a menu to rename, edit or remove it. Tapping another switches to it. "Add a playlist" opens the same three cards. Adding a playlist makes it the one in use. Editing the one in use reloads it; editing another only saves it. Removing the one in use switches to the next; removing the last one signs out (with a warning) and shows the welcome screen. The Settings row reads the playlist in use and how many are saved. The old Server and Username rows are gone, since they meant nothing for a link or a file; the server is edited from the playlist's Edit.

**One playlist at a time.** The app still holds one playlist's channels and guide (switching clears them and downloads the other), so lists, search and favourites never mix providers and the database stays small. When the first download fails, "Change playlist" opens Settings › Playlists instead of signing out.

## Sign out

**Signing out resets Tuned to how it was installed** (Melvin, 2026-11-19: "sign out should forget every playlist, downloaded channels, disconnects trakt, google drive, anything else that syncs, all settings", and "it should also remove who is using the account users, basically it should just reset the app to a preinstalled state"). Settings › Playlists › Sign out (`data/AppReset`) removes:

- every saved playlist and login, the OpenSubtitles account, and copied playlist files;
- the cached channels, guide and catch-up, and every table in the database (favourites, history, watch later, your own lists, channel edits, guide fixes, channel health), read from the database itself so a table added later is emptied too;
- every "Who's watching" profile, its avatar and PIN, and the parental PIN; one empty profile is left in use, as on a fresh install;
- every setting, the learned intros, the channel scan report, the TV home screen rows, subtitles, the picture cache and the app's cache;
- Trakt and Google Drive: forgotten on this device. Neither service is asked to revoke its token, because sync shares the Trakt sign-in across the person's devices, and Google revokes the whole grant, so revoking would sign their TV out too. All background work stops first, so sync can never push the emptied app to Drive.

**What stays:** Premium, which belongs to the Play account and the device seat rather than a playlist (Play is asked again straight after); the crash reports and log in Settings › Updates and advanced, for support; and **the synced copy in Google Drive**, which is the person's own file in their own Google account. Sign-out only disconnects it, so signing back in to Drive on this or another device can bring everything back. Deleting it would be a separate, explicit choice.

**Downloads are asked about** (Melvin, 2026-11-19): when any are saved, a second step asks "Also delete 12 downloads (8.4 GB)?", counting downloads and recordings and the space they take on the device, with **Delete** (the default, the filled button) and **Keep**. Backing out cancels the sign-out. Keep leaves finished downloads and recordings, with the TMDB details and artwork saved for them, so they still play offline; their ids come from the stream, so they join their films and episodes again when the same provider signs back in. Unfinished ones go, since finishing them needs the login, and every saved stream address (which can hold the login) is forgotten.

**It can't stop half way.** The reset writes a marker before it removes anything and clears it only once every step succeeded; every step is safe to run twice. If the app is closed or crashes part way, the login is forgotten as the app next starts, before the first screen is chosen, and the rest finishes in the background. The database is emptied in one transaction.

Removing the last playlist in Settings › Playlists still only forgets the playlist and its channels; Sign out is the full reset.

**Sync between devices** carries every saved playlist except files, and everything else the person set up ([sync](sync.md)). Backup files were removed (Melvin, 2026-11-18).
