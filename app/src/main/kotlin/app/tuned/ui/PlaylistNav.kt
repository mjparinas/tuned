package app.tuned.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.tuned.container
import app.tuned.data.Credentials
import app.tuned.ui.onboarding.AddPlaylistScreen
import app.tuned.ui.onboarding.SendToTvScreen
import app.tuned.ui.onboarding.TvSignInScreen
import app.tuned.ui.onboarding.WelcomeScreen
import app.tuned.ui.settings.PlaylistsScreen
import app.tuned.work.TvHome
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

/** On a TV: sign in with a phone (docs/decisions/onboarding.md). */
@Serializable data object TvSignInRoute

/** On a phone: send this phone's playlists and settings to a TV, from the TV's QR [link] or a typed code. */
@Serializable data class SendToTvRoute(
    val link: String = "",
)

/** Adding, choosing and managing playlists. */
fun NavGraphBuilder.playlistScreens(app: AppNav) {
    composable<WelcomeRoute> {
        WelcomeScreen(
            firstRun = true,
            onPick = { kind -> app.nav.navigate(AddPlaylistRoute(kind.name)) },
            onBack = {},
            onPhone = phoneSignIn(app),
        )
    }
    composable<ChoosePlaylistRoute> {
        WelcomeScreen(
            firstRun = false,
            onPick = { kind -> app.nav.navigate(AddPlaylistRoute(kind.name)) },
            onBack = { app.nav.popBackStack() },
            onPhone = phoneSignIn(app),
        )
    }
    composable<TvSignInRoute> {
        TvSignInScreen(onBack = { app.nav.popBackStack() }, onDone = app.openHome)
    }
    composable<SendToTvRoute> { entry ->
        SendToTvScreen(link = entry.toRoute<SendToTvRoute>().link, onBack = { app.nav.popBackStack() })
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
            onSendToTv = if (TvHome.isTv(context)) null else ({ app.nav.navigate(SendToTvRoute()) }),
            onSwitched = app.openHome,
            onEmpty = app.openWelcome,
        )
    }
}

/** "Sign in with your phone" leads the welcome screen on a TV only; a phone types faster than it scans. */
@Composable
private fun phoneSignIn(app: AppNav): (() -> Unit)? {
    val context = LocalContext.current
    return if (TvHome.isTv(context)) ({ app.nav.navigate(TvSignInRoute) }) else null
}
