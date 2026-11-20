package com.tunedplayer.app.screenshots

import com.tunedplayer.app.R
import com.tunedplayer.app.data.Credentials
import com.tunedplayer.app.ui.AddPlaylistRoute
import com.tunedplayer.app.ui.SendToTvRoute
import com.tunedplayer.app.ui.TvSignInRoute
import com.tunedplayer.app.ui.WelcomeRoute
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
