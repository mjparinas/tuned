package com.tunedplayer.app.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.tunedplayer.app.ui.browse.HomeScreen
import com.tunedplayer.app.ui.details.MovieScreen
import com.tunedplayer.app.ui.details.PersonScreen
import com.tunedplayer.app.ui.events.EventsScreen
import com.tunedplayer.app.ui.guide.GuideScreen
import com.tunedplayer.app.ui.offline.DownloadsScreen
import com.tunedplayer.app.ui.search.SearchScreen
import com.tunedplayer.app.ui.settings.ProfilesScreen
import com.tunedplayer.app.ui.shows.ShowScreen
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/** "Who's watching?": the first screen when [atStart], else opened from Home or Settings to switch or manage. */
@Serializable data class ProfilesRoute(
    val atStart: Boolean = false,
)

@Serializable data object HomeRoute

@Serializable data class SearchRoute(
    val query: String = "",
)

@Serializable data class ShowRoute(
    val showKey: String,
    val title: String,
)

/** A film's details page (rating, summary, trailer). */
@Serializable data class MovieRoute(
    val itemId: Long,
)

/** An actor's films and shows. [personId] is TMDB's, or 0 when only the name is known. */
@Serializable data class PersonRoute(
    val personId: Long,
    val name: String,
)

/** Live Events: today's games, fights and pay-per-views. */
@Serializable data object EventsRoute

@Serializable data class GuideRoute(
    val itemId: Long,
)

/** Downloads on its own, from the offline banner on any other screen; on Home it is a section beside the tabs. */
@Serializable data object DownloadsRoute

/** Home, search, details, the guide, events, downloads and profiles. */
fun NavGraphBuilder.browseScreens(app: AppNav) {
    composable<ProfilesRoute> { entry ->
        val atStart = entry.toRoute<ProfilesRoute>().atStart
        ProfilesScreen(
            atStart = atStart,
            onPicked = {
                if (atStart || !app.nav.popBackStack(HomeRoute, inclusive = false)) {
                    app.nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                }
            },
            onBack = { app.nav.popBackStack() },
        )
    }
    composable<HomeRoute> {
        HomeScreen(
            settings = app.settings,
            onPlay = app.play,
            onOpenShow = { key, title -> app.nav.navigate(ShowRoute(key, title)) },
            onOpenGuide = { id -> app.nav.navigate(GuideRoute(id)) },
            onOpenMovie = { id -> app.nav.navigate(MovieRoute(id)) },
            onOpenPerson = app.openPerson,
            onOpenSettings = { app.nav.navigate(SettingsRoute) },
            onOpenProfiles = { app.nav.navigate(ProfilesRoute()) },
            onOpenSearch = { app.nav.navigate(SearchRoute(it)) },
            onOpenEvents = { app.nav.navigate(EventsRoute) },
            onTune = app.tune,
            onOpenPlaylists = { app.nav.navigate(PlaylistsRoute) },
        )
    }
    composable<SearchRoute> { entry ->
        SearchScreen(
            initialQuery = entry.toRoute<SearchRoute>().query,
            onBack = { app.nav.popBackStack() },
            onPlay = app.play,
            onOpenShow = { key, title -> app.nav.navigate(ShowRoute(key, title)) },
            onOpenMovie = { id -> app.nav.navigate(MovieRoute(id)) },
            onTune = app.tune,
        )
    }
    composable<ShowRoute> { entry ->
        val route = entry.toRoute<ShowRoute>()
        ShowScreen(
            showKey = route.showKey,
            title = route.title,
            onPlay = app.play,
            onBack = { app.nav.popBackStack() },
            onOpenPerson = app.openPerson,
        )
    }
    composable<MovieRoute> { entry ->
        MovieScreen(
            itemId = entry.toRoute<MovieRoute>().itemId,
            onPlay = app.play,
            onBack = { app.nav.popBackStack() },
            onOpenPerson = app.openPerson,
        )
    }
    composable<PersonRoute> { entry ->
        val route = entry.toRoute<PersonRoute>()
        PersonScreen(
            personId = route.personId,
            name = route.name,
            onOpenMovie = { id -> app.nav.navigate(MovieRoute(id)) },
            onOpenShow = { key, title -> app.nav.navigate(ShowRoute(key, title)) },
            onBack = { app.nav.popBackStack() },
        )
    }
    composable<EventsRoute> { EventsScreen(onTune = app.tune, onBack = { app.nav.popBackStack() }) }
    composable<GuideRoute> { entry ->
        GuideScreen(itemId = entry.toRoute<GuideRoute>().itemId, onPlay = app.play, onBack = { app.nav.popBackStack() })
    }
    composable<DownloadsRoute> {
        DownloadsScreen(onPlay = app.play, onBack = { app.nav.popBackStack() })
    }
}
