package app.tuned.ui

import kotlinx.serialization.Serializable

@Serializable data object LoginRoute

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

@Serializable data object FavouriteOrderRoute

@Serializable data class SearchRoute(
    val query: String = "",
)

/** A film's details page (rating, summary, trailer). */
@Serializable data class MovieRoute(
    val itemId: Long,
)
