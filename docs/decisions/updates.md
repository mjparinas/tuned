# Updates

**Google Play in-app updates** (Melvin chose, 2026-11-17, over a GitHub Releases self-updater). `release.yml` uploads every promotion to the Play internal track with in-app update priority 5. On each start Tuned asks Play; when a newer build exists it installs it straight away (an immediate update), or where Play allows only a flexible update, downloads it in the background and restarts into it once ready. An interrupted update resumes on the next return to the app. Debug and sideloaded builds get "no update" from Play and carry on.
