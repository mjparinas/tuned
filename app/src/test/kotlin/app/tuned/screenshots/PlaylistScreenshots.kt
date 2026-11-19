package app.tuned.screenshots

import app.tuned.R
import app.tuned.data.Credentials
import app.tuned.ui.AddPlaylistRoute
import app.tuned.ui.SendToTvRoute
import app.tuned.ui.TvSignInRoute
import app.tuned.ui.WelcomeRoute
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The welcome screen, the forms for adding a playlist, and signing in on a TV with a phone. */
@RunWith(RobolectricTestRunner::class)
class PlaylistScreenshots : Screenshots() {
    @Test fun welcome() = shot("playlist-welcome", WelcomeRoute, wait = text(R.string.welcome_title))

    // The link form, not the Xtream one: the github build fills the Xtream form with a real server address, which
    // screenshots never show (docs/decisions/premium.md).
    @Test
    fun addLink() = shot("playlist-add-link", AddPlaylistRoute(Credentials.Kind.Link.name), wait = text(R.string.login_playlist_url))

    /** The TV's QR code and code, from a stand-in relay that hands out a made-up code and keeps the TV waiting. */
    @Test
    @Config(qualifiers = TV)
    fun tvSignIn() {
        MockWebServer().use { relay ->
            relay.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/json")
                        .body(
                            if (request.url.encodedPath.endsWith("/code")) {
                                """{"status":"ok","code":"K7QF29MD","secret":"s","seconds":600}"""
                            } else {
                                """{"status":"waiting"}"""
                            },
                        ).build()
                }
            relay.start()
            app.container.tvSignInUrl = relay.url("/").toString().trimEnd('/')
            shot("playlist-tv-sign-in", TvSignInRoute, wait = "K7QF-29MD")
        }
    }

    @Test fun sendToTv() = shot("playlist-send-to-tv", SendToTvRoute(), wait = text(R.string.send_tv_code))
}
