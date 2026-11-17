# Speed

Melvin (2026-11-17): "prioritize speed and efficiency when using the app."

- **Open to content.** The first screen reads the cached playlist; nothing waits on the network. The splash holds only until settings load (milliseconds). The database, HTTP client and image loader are built on first use.
- **Paged lists.** Live TV, Movies and Shows page straight from Room (60 per page, placeholders on), so tens of thousands of streams scroll smoothly and search is a single indexed query.
- **Streaming imports.** Playlists and guides are parsed line by line and inserted in batches of short transactions; memory stays flat whatever the size, and lists and search never wait on a refresh (see [playlist](playlist.md)).
- **Images.** Coil with a 20% memory cache and a 256 MB disk cache, through the shared OkHttp client.
- **Player start.** Fast-start TS extractor flags, a Fast start buffer mode, and Media3's own PlayerView.
- **Release builds** are shrunk with R8 and ship a baseline profile installer (profileinstaller).
