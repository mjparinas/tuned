package com.tunedplayer.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * On a TV, Settings opens with its Playlists page beside the list, so its account status asks the server the moment
 * Settings opens, often while the first playlist download is still running. A failure in that ask that wasn't an
 * IOException (an address OkHttp can't parse, a client that throws) ended the app (Melvin, 2026-11-20: "on a tv, while
 * the channels were loading, when i went to settings, the app crashed"). Asking must never throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsRepository(context)
    private val server = MockWebServer().apply { start() }

    @After
    fun stop() = server.close()

    private fun repo(
        login: Credentials,
        http: OkHttpClient = OkHttpClient(),
    ) = AccountRepository(http, { login }, settings, MutableStateFlow(false))

    private fun xtream(server: String) = Credentials(server, "user", "secret")

    @Test
    fun aServerAddressOkHttpCantParseDoesNotThrow() = runTest {
        val account = repo(xtream("http://provider.example:99999"))

        account.refresh()

        assertNull(account.snapshot.value)
    }

    @Test
    fun aFailureThatIsNotANetworkErrorDoesNotThrow() = runTest {
        val broken =
            OkHttpClient
                .Builder()
                .addInterceptor { error("the client broke") }
                .build()
        val account = repo(xtream(server.url("/").toString()), broken)

        account.refresh()

        assertNull(account.snapshot.value)
    }

    @Test
    fun aGoodAnswerStillShows() = runTest {
        server.enqueue(
            MockResponse
                .Builder()
                .body("""{"user_info":{"auth":1,"status":"Active","active_cons":"0","max_connections":"2"}}""")
                .build(),
        )
        val account = repo(xtream(server.url("/").toString()))

        account.refresh()

        val info = account.snapshot.value?.info
        assertEquals("Active", info?.status)
        assertEquals(2, info?.maxConnections)
    }
}
