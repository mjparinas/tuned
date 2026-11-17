# Security

**The login** (Melvin, 2026-11-17: "cache the username/password in the app permanently unless they sign out") is stored until Settings › Sign out, encrypted with AES-256-GCM using a key that lives in the Android Keystore and never leaves it (`CredentialStore`). Jetpack Security's EncryptedSharedPreferences is deprecated, so the Keystore is used directly. Signing out deletes the login, the cached playlist and guide, and cancels background refreshes.

**Never in chat, logs or the repo.** Nobody asks for the login outside the app. Errors that might contain a URL pass through `XtreamUrls.redact`. `.gitignore` blocks playlists, guides and `.env` files. Testing against the real server uses a local `.env` on Melvin's machine.

**Backups are off** (`allowBackup=false` and data extraction rules that exclude everything), so neither the encrypted login nor the cached URLs (which contain it) reach a cloud backup or a device transfer.

**Cleartext HTTP is allowed** (`network_security_config.xml`), because IPTV servers, eros4k.ddns.net included, serve playlists and streams over plain HTTP on whatever host a stream redirects to. Android Lint's `InsecureBaseConfiguration` check is disabled for that reason.
