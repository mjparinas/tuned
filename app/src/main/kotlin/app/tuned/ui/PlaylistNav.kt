package app.tuned.ui

import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.tuned.container
import app.tuned.data.Credentials
import app.tuned.ui.onboarding.AddPlaylistScreen
import app.tuned.ui.onboarding.WelcomeScreen
import app.tuned.ui.settings.PlaylistsScreen
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

/** Adding, choosing and managing playlists. */
fun NavGraphBuilder.playlistScreens(app: AppNav) {
    composable<WelcomeRoute> {
        WelcomeScreen(
            firstRun = true,
            onPick = { kind -> app.nav.navigate(AddPlaylistRoute(kind.name)) },
            onRestored = app.openHome,
            onBack = {},
        )
    }
    composable<ChoosePlaylistRoute> {
        WelcomeScreen(
            firstRun = false,
            onPick = { kind -> app.nav.navigate(AddPlaylistRoute(kind.name)) },
            onRestored = app.openHome,
            onBack = { app.nav.popBackStack() },
        )
    }
    composable<AddPlaylistRoute> { entry ->
        val route = entry.toRoute<AddPlaylistRoute>()
        AddPlaylistScreen(
            kind = Credentials.Kind.valueOf(route.kind),
            editIndex = route.editIndex,
            onBack = { app.nav.popBackStack() },
            // A new playlist (or an edit of the one in use) opens on its own; an edit of another goes back.
            onDone = { opened -> if (opened) app.openHome() else app.nav.popBackStack() },
        )
    }
    composable<PlaylistsRoute> {
        val context = LocalContext.current
        PlaylistsScreen(
            onBack = { app.nav.popBackStack() },
            // A second playlist is Premium (docs/decisions/premium.md).
            onAdd = { if (context.container.premium.allow(context)) app.nav.navigate(ChoosePlaylistRoute) },
            onEdit = { index, kind -> app.nav.navigate(AddPlaylistRoute(kind.name, index)) },
            onSwitched = app.openHome,
            onEmpty = app.openWelcome,
        )
    }
}
