# Favourites and your own lists

**Lists like a music app's playlists** (Melvin, 2026-11-19: "groups should really be similar to how spotify does it. you have favourite a channel and that adds it to a favourite playlist, but then you can make your own playlists. so favourite is basically a playlist that you can add to by default but you can make your own"). They're called lists in the app, because "playlist" already means the provider's playlist (Settings › Playlists).

- **Favourites is the built-in list.** The heart adds to it in one tap, as before, and it keeps its own table (`favourites`), order (Settings › Order favourites), widget and Android TV row. Nothing about it moved, so no favourite was lost. It stays free for everyone (Melvin, 2026-11-19: "favourites should be unlocked for everyone though").
- **Your own lists** sit beside it (`lists` and `list_items`, Room v18). "Add to list" in a channel's or film's long-press menu ticks the lists it belongs in, or makes a new one with it already inside. Each list is a chip straight after Favourites on Live TV and Movies, shown on a tab only once it holds something of that kind. The chosen chip's menu (or a screen reader's actions on it) renames or deletes the list; deleting a list leaves its channels and films where they are. Channels show in the order they were added; films in playlist order. Shows aren't added to lists, as they can't be favourites either.
- A list holds stable ids, like a favourite, so a refresh keeps it. Each profile has its own lists, deleting a profile deletes them, and sync between devices carries the profile's lists along with its favourites ([sync](sync.md)). A list's id is chosen at random when it's made, so the same list has the same id on every device.
- **Premium** in the store build: making and using your own lists. A locked "Add to list" opens the Premium screen ([premium](premium.md)).

## Renaming a channel and changing its logo

Melvin, 2026-11-19: "do the rename if people want it. yes it should be premium." A live channel's long-press menu has **Rename channel** and **Change logo**.

- A new name shows everywhere at once: the lists, search, the guide, the player and the widget, because it is written over the cached channel (`channel_edits`, Room v19, and `ChannelEditor`). Search finds the provider's name as well as the new one. "Use the original name" puts the provider's back.
- A new logo is one of the logos other copies of the channel use (the HD copy's logo for an FHD copy that has none), or a picture link. "Use the original logo" puts the provider's back.
- Each refresh lays the edits over the provider's copy before the cache takes it (`ChannelEdits.applyTo`), noting the provider's current name and logo as the originals, so a refresh never undoes an edit. Backup copies and the HQ button still match a renamed channel by the provider's name, so renaming "US| ESPN FHD" to "ESPN" doesn't lose its copies.
- Edits belong to the playlist, not a profile (a channel is called the same whoever watches), and sync carries them.
- Premium in the store build, like your own lists.
