# Trakt

From the app audit, Melvin picked Trakt (item 14): what's watched in Tuned shows up in the person's Trakt history and "up next", alongside every other app they use.

**Signing in** (`data/TraktAccount`). Trakt's device code: Settings › Playlists and account › Connect Trakt shows a short code and trakt.tv/activate, which works the same from a phone, a computer or a TV remote, and Tuned never sees the Trakt password. The dialog asks Trakt every few seconds (slower when Trakt says so) until the code is entered, refused or runs out. The tokens are sealed with a key in the Android Keystore (`data/KeystoreBox`), like the login, and stay out of backups. They are renewed a day before they expire; if Trakt no longer accepts them (revoked on trakt.tv), Tuned signs out of Trakt too. Disconnecting tells Trakt to drop the token.

**Scrobbling** (`player/TraktScrobbler`, `core/Trakt`, with tests). Films and episodes only: start when one plays, pause when the person pauses (buffering isn't a pause), stop when it ends, the next one starts or the player closes, each with how far through it is. Trakt marks it watched from 80%. Trakt matches by title and year (films) or show title and year with the season and episode number, so no TMDB key is needed; names are cleaned of the provider's tags the same way as for ratings. Live TV, catch-up and recordings aren't sent. A failed send is logged and dropped; the next play or pause sends the state again. Settings › Playlists and account › Scrobble what you watch turns it off without disconnecting (on by default).

**The app's keys.** Trakt gives each app a client id and secret. They come from the release workflow's secrets `TRAKT_CLIENT_ID` and `TRAKT_CLIENT_SECRET` into `BuildConfig`, and are never committed. A build without them (debug builds, pull requests) hides everything Trakt. Device-code apps ship the secret inside the app by Trakt's design; it identifies the app, not the person.

**Not done yet:** importing Trakt's watched history into Tuned's own (marking episodes watched that were seen elsewhere). To revisit once scrobbling has been used for a while.
