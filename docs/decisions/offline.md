# Offline downloads

**What** (backlog, 2026-11-17). Films and episodes can be saved to the device and watched without a connection. A download button sits on the film page and beside each episode; Settings, Downloads lists everything saved, with progress, retry and remove.

**One connection at a time** (Melvin, 2026-11-17: the app never holds more than one stream connection). Downloads run one after another in a WorkManager job, and the job is cancelled the moment the player opens. It starts again when the player closes; the film that was interrupted starts over, since a stopped download's partial file isn't kept. A dropped connection during a download is retried and resumed with an HTTP range request when the server allows it.

**Where files go.** App-specific external storage (`getExternalFilesDir("offline")`), so no storage permission is needed and Android removes the files with the app. They aren't in backups.

**Network.** Downloads follow the existing "Wi-Fi only" setting for refreshes rather than adding a second one: a person who keeps refreshes off mobile data wants the same for gigabyte-sized files.

**Playing.** When a film or episode has a finished download, the player plays the file instead of the stream, with no other change. Live channels can't be downloaded.

**Database.** Rows live in the `downloads` table (Room v6), keyed by the item's stable id, so they survive playlist refreshes like favourites do. Switching to a different playlist clears its items, so its downloads stay listed but can't be opened until that playlist is used again.
