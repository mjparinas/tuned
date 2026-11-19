package app.tuned.screenshots

import app.tuned.R
import app.tuned.data.Credentials
import app.tuned.ui.AddPlaylistRoute
import app.tuned.ui.WelcomeRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The welcome screen and the forms for adding a playlist. */
@RunWith(RobolectricTestRunner::class)
class PlaylistScreenshots : Screenshots() {
    @Test fun welcome() = shot("playlist-welcome", WelcomeRoute, wait = text(R.string.welcome_title))

    @Test
    @Config(qualifiers = TV)
    fun welcomeTv() = shot("playlist-welcome-tv", WelcomeRoute, wait = text(R.string.welcome_title))

    // No build fills in a server any more (Melvin, 2026-11-19), so the Xtream form can be drawn: it starts empty.
    @Test
    fun addXtream() = shot("playlist-add-xtream", AddPlaylistRoute(Credentials.Kind.Xtream.name), wait = text(R.string.add_server_help))

    @Test
    fun addLink() = shot("playlist-add-link", AddPlaylistRoute(Credentials.Kind.Link.name), wait = text(R.string.login_playlist_url))

    @Test
    fun addPortal() = shot("playlist-add-portal", AddPlaylistRoute(Credentials.Kind.Stalker.name), wait = text(R.string.login_mac))
}
