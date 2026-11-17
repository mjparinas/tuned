package app.tuned.ui

import kotlinx.serialization.Serializable

/** First run: what kind of playlist to add. */
@Serializable data object WelcomeRoute

/** Settings › Playlists › Add: the same question as [WelcomeRoute], with a back arrow. */
@Serializable data object ChoosePlaylistRoute

/** The form for one kind of playlist ([app.tuned.data.Credentials.Kind] by name); [editIndex] edits a saved one. */
@Serializable data class AddPlaylistRoute(
    val kind: String,
    val editIndex: Int = -1,
)

/** Settings › Playlists. */
@Serializable data object PlaylistsRoute

@Serializable data object HomeRoute

@Serializable data class ShowRoute(
    val showKey: String,
    val title: String,
)

@Serializable data class GuideRoute(
    val itemId: Long,
)

@Serializable data object SettingsRoute

@Serializable data object CaptionSettingsRoute

@Serializable data object RegionSettingsRoute

@Serializable data object HiddenGroupsRoute

@Serializable data object ChannelHealthRoute

@Serializable data object FavouriteOrderRoute

@Serializable data class SearchRoute(
    val query: String = "",
)

/** A film's details page (rating, summary, trailer). */
@Serializable data class MovieRoute(
    val itemId: Long,
)

/** Settings › Developer › Logs. */
@Serializable data object DeveloperLogRoute

/** Settings › Downloads. */
@Serializable data object DownloadsRoute
