package app.tuned.screenshots

import app.tuned.R
import app.tuned.data.Credentials
import app.tuned.ui.AddPlaylistRoute
import app.tuned.ui.WelcomeRoute
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The welcome screen and the forms for adding a playlist. */
@RunWith(RobolectricTestRunner::class)
class PlaylistScreenshots : Screenshots() {
    @Test fun welcome() = shot("playlist-welcome", WelcomeRoute, wait = text(R.string.welcome_title))

    // The link form, not the Xtream one: the github build fills the Xtream form with a real server address, which
    // screenshots never show (docs/decisions/premium.md).
    @Test
    fun addLink() = shot("playlist-add-link", AddPlaylistRoute(Credentials.Kind.Link.name), wait = text(R.string.login_playlist_url))

    @Test
    fun addPortal() = shot("playlist-add-portal", AddPlaylistRoute(Credentials.Kind.Stalker.name), wait = text(R.string.login_mac))
}
