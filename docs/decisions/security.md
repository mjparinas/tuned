# Security

**The login** (Melvin, 2026-11-17: "cache the username/password in the app permanently unless they sign out") is stored until Settings › Sign out, encrypted with AES-256-GCM using a key that lives in the Android Keystore and never leaves it (`CredentialStore`). Jetpack Security's EncryptedSharedPreferences is deprecated, so the Keystore is used directly. Signing out deletes the login, the cached playlist and guide, and cancels background refreshes.

**Never in chat, logs or the repo.** Nobody asks for the login outside the app. Errors that might contain a URL pass through `XtreamUrls.redact`. `.gitignore` blocks playlists, guides and `.env` files. Testing against the real server uses a local `.env` on Melvin's machine.

**Masking the login in logs** also covers its link form (`p%40ss` for `p@ss`, 2026-11-18): an Xtream playlist's live links are `http://host/<user>/<pass>/<id>`, with no `/live/` for the pattern scrubber to spot, so a password with symbols in it reached the log in Settings.

**Third-party CI actions that receive a secret are pinned to a commit**, not a tag (2026-11-18), so a moved tag can't swap in other code: today that is the Play upload action, which holds the Play service account.

**Backups are off** (`allowBackup=false` and data extraction rules that exclude everything), so neither the encrypted login nor the cached URLs (which contain it) reach a cloud backup or a device transfer.

**Backup files: removed** (Melvin, 2026-11-18: "Remove the file back up feature"). Settings › Back up and Restore, and "Restore from a backup" on the welcome screen, are gone; moving to a new phone or a TV is the backup in the Tuned account ([accounts](accounts.md)). Files made with it no longer open. Its encryption, `core/PassphraseCrypto`, was removed with Google Drive sync (2026-11-19).

**Privacy policy** (2026-11-18). `docs/privacy.md`, published with `docs/index.md` through GitHub Pages, is the public policy Google's consent screen needs before sync's OAuth app can be published (and the Play listing will need). It says what stays on the device, which services Tuned talks to and why, and how the synced copy is locked. A change to what Tuned stores or sends updates it in the same pull request.

**Cleartext HTTP is allowed** (`network_security_config.xml`), because IPTV servers serve playlists and streams over plain HTTP on whatever host a stream redirects to. Android Lint's `InsecureBaseConfiguration` check is disabled for that reason.

**Serving saved files to a TV** (2026-11-18). Casting a saved film or episode opens a small HTTP server on the phone (`core/FileServer`), reachable on the local network while the cast lasts and closed when it ends or the player closes. It serves only the files handed to it, under a path with 96 random bits, and nothing else on the device; the files hold no login.

## Parental PIN (2026-11-17)

Melvin asked for "a pin to filter out adult content". Adult categories (the classifier flags groups such as "FOR ADULTS", XXX and 18+) are always hidden until unlocked with a PIN of 4 to 8 digits:

- The PIN is stored only as a salted PBKDF2-SHA256 hash (`PinHash`, 120,000 rounds) in DataStore, never as the digits.
- The unlock lives in memory only. It ends when the app process ends, or 5 minutes after the app leaves the screen, so a phone handed over later shows nothing adult.
- Five wrong PINs in a row wait 30 seconds before the next try.
- "Forgot PIN" takes the account password (or, for a playlist link login, the link) and then sets a new PIN. Signing out removes the PIN with everything else (Melvin, 2026-11-19: sign-out resets the app "to a preinstalled state"; until then the PIN stayed across sign-out). See [onboarding](onboarding.md#sign-out).

**Saved playlists** (2026-11-17). Every login used on the device is kept, encrypted with the same Keystore key as the one in use, so Settings › Playlists can switch between them without typing them again. Switching to another provider clears the cached channels, guide and catch-up list and downloads the other playlist, so the lists never mix and the app still holds one playlist at a time. Signing out forgets all of them, and deletes playlist files copied into the app. The synced copy holds every saved playlist except files ([sync](sync.md)).

**Signing in on a TV with a phone** (2026-11-19). The one place outside the app that takes a login is the page at `tuned-devices.vercel.app/tv`, and it seals the login on the phone for one TV before sending, so the relay only holds a box it can't open, for 10 minutes at most ([onboarding](onboarding.md)). What the phone app sends that way (playlists, settings, favourites) is sealed the same way.

**The backup in the Tuned account** (2026-11-19). It holds the saved playlists' logins as plain JSON on Tuned's server, not encrypted (Melvin, 2026-11-19: "i dont want to encrypt backups"), reachable only by devices signed in to the account. The device's account token is sealed with a Keystore key and stored only hashed on the server ([accounts](accounts.md)). Google Drive sync, which kept the same copy in a file in the person's Drive, was removed the same day.

**OpenSubtitles account** (2026-11-17). The optional sign-in for subtitle search is encrypted with the same Keystore key as the login (`CredentialStore.subtitleAccount`), stays out of settings and sync, and is forgotten on sign-out. The API key is not a setting: it comes from the `OPENSUBTITLES_API_KEY` repository secret at release build time, like the TMDB key.
