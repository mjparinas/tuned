# Security

**The login** (Melvin, 2026-11-17: "cache the username/password in the app permanently unless they sign out") is stored until Settings › Sign out, encrypted with AES-256-GCM using a key that lives in the Android Keystore and never leaves it (`CredentialStore`). Jetpack Security's EncryptedSharedPreferences is deprecated, so the Keystore is used directly. Signing out deletes the login, the cached playlist and guide, and cancels background refreshes.

**Never in chat, logs or the repo.** Nobody asks for the login outside the app. Errors that might contain a URL pass through `XtreamUrls.redact`. `.gitignore` blocks playlists, guides and `.env` files. Testing against the real server uses a local `.env` on Melvin's machine.

**Backups are off** (`allowBackup=false` and data extraction rules that exclude everything), so neither the encrypted login nor the cached URLs (which contain it) reach a cloud backup or a device transfer.

**Backup files: removed** (Melvin, 2026-11-18: "Remove the file back up feature"). Settings › Back up and Restore, and "Restore from a backup" on the welcome screen, are gone; moving to a new phone or a TV is sync between devices ([sync](sync.md)). Files made with it no longer open. Its encryption stays as `core/PassphraseCrypto` (AES-256-GCM, PBKDF2-SHA256 with 310,000 rounds and a fresh salt each time, header authenticated), which now seals only the synced copy.

**Privacy policy** (2026-11-18). `docs/privacy.md`, published with `docs/index.md` through GitHub Pages, is the public policy Google's consent screen needs before sync's OAuth app can be published (and the Play listing will need). It says what stays on the device, which services Tuned talks to and why, and how the synced copy is locked. A change to what Tuned stores or sends updates it in the same pull request.

**Cleartext HTTP is allowed** (`network_security_config.xml`), because IPTV servers, eros4k.ddns.net included, serve playlists and streams over plain HTTP on whatever host a stream redirects to. Android Lint's `InsecureBaseConfiguration` check is disabled for that reason.

**Serving saved files to a TV** (2026-11-18). Casting a saved film or episode opens a small HTTP server on the phone (`core/FileServer`), reachable on the local network while the cast lasts and closed when it ends or the player closes. It serves only the files handed to it, under a path with 96 random bits, and nothing else on the device; the files hold no login.

## Parental PIN (2026-11-17)

Melvin asked for "a pin to filter out adult content". Adult categories (the classifier flags groups such as "FOR ADULTS", XXX and 18+) are always hidden until unlocked with a PIN of 4 to 8 digits:

- The PIN is stored only as a salted PBKDF2-SHA256 hash (`PinHash`, 120,000 rounds) in DataStore, never as the digits.
- The unlock lives in memory only. It ends when the app process ends, or 5 minutes after the app leaves the screen, so a phone handed over later shows nothing adult.
- Five wrong PINs in a row wait 30 seconds before the next try.
- "Forgot PIN" takes the account password (or, for a playlist link login, the link) and then sets a new PIN. The PIN stays across sign-out, because it guards the device rather than one login.

**Saved playlists** (2026-11-17). Every login used on the device is kept, encrypted with the same Keystore key as the one in use, so Settings › Playlists can switch between them without typing them again. Switching to another provider clears the cached channels, guide and catch-up list and downloads the other playlist, so the lists never mix and the app still holds one playlist at a time. Signing out forgets all of them, and deletes playlist files copied into the app. The synced copy holds every saved playlist except files ([sync](sync.md)).

**Sync between devices** (2026-11-18). The synced copy holds the saved playlists' logins, so it is sealed with the person's sync passphrase before it reaches Google Drive (`core/PassphraseCrypto`); the passphrase and the Google refresh token are sealed with Keystore keys and are never synced ([sync](sync.md)).

**OpenSubtitles account** (2026-11-17). The optional sign-in for subtitle search is encrypted with the same Keystore key as the login (`CredentialStore.subtitleAccount`), stays out of settings and sync, and is forgotten on sign-out. The API key is not a setting: it comes from the `OPENSUBTITLES_API_KEY` repository secret at release build time, like the TMDB key.
