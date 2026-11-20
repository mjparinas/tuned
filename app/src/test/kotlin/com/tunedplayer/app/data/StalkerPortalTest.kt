package com.tunedplayer.app.data

import com.tunedplayer.app.core.Programme
import com.tunedplayer.app.core.Stalker
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A fake portal on the loopback that answers like a Stalker (Ministra) server under `/portal.php`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StalkerPortalTest {
    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val mac = "00:1A:79:AB:CD:EF"

    /** How many token checks fail before the portal takes the token again ("Authorization failed."). */
    @Volatile private var refuseTokens = 0

    @Volatile private var knownMac = true

    @Volatile private var handshakes = 0

    @Before fun start() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    if (request.url.encodedPath != "/portal.php") return MockResponse.Builder().code(404).build()
                    val action = request.url.queryParameter("action")
                    if (action == "handshake") {
                        handshakes++
                        return json(if (knownMac) """{"js":{"token":"T$handshakes"}}""" else """{"js":{}}""")
                    }
                    if (action != "get_profile" && refuseTokens > 0) {
                        refuseTokens--
                        return MockResponse.Builder().body("Authorization failed.").build()
                    }
                    return when (action) {
                        "get_profile" -> {
                            json("""{"js":{"id":"1","blocked":"0"}}""")
                        }

                        "get_genres" -> {
                            json("""{"js":[{"id":"1","title":"US| NEWS"},{"id":"2","title":"SPORTS"}]}""")
                        }

                        "get_all_channels" -> {
                            json(
                                """{"js":{"total_items":3,"data":[
                                {"id":"10","name":"CNN","number":"1","cmd":"ffrt http://localhost/ch/10_","logo":"/misc/10.png",
                                 "tv_genre_id":"1","xmltv_id":"cnn.us","extra":{"nested":[1,2]}},
                                {"id":11,"name":"Sports 1","cmd":"ffmpeg http://cdn.example/11.m3u8","logo":"","tv_genre_id":"2"},
                                {"id":"12","name":"","cmd":"x"}
                                ]}}""",
                            )
                        }

                        "create_link" -> {
                            json("""{"js":{"cmd":"ffmpeg http://cdn.example/play/10.ts?play_token=abc"}}""")
                        }

                        "get_epg_info" -> {
                            json(
                                """{"js":{"data":{"10":[
                                {"name":"News","descr":"Headlines","start_timestamp":1700000000,"stop_timestamp":1700003600},
                                {"name":"","start_timestamp":1700003600,"stop_timestamp":1700007200}
                                ]}}}""",
                            )
                        }

                        else -> {
                            MockResponse.Builder().code(404).build()
                        }
                    }
                }
            }
        server.start()
    }

    @After fun stop() = server.close()

    private fun json(body: String) = MockResponse
        .Builder()
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun portal() = StalkerPortal(OkHttpClient(), server.url("/c/").toString(), mac)

    @Test
    fun `signing in sends the box's MAC, and the token after the handshake`() {
        assertTrue(portal().signIn())
        val handshake = seen.first { it.url.queryParameter("action") == "handshake" }
        assertEquals(Stalker.cookie(mac), handshake.headers["Cookie"])
        assertEquals(Stalker.USER_AGENT, handshake.headers["User-Agent"])
        val profile = seen.first { it.url.queryParameter("action") == "get_profile" }
        assertEquals("Bearer T1", profile.headers["Authorization"])
        assertEquals("MAG250", profile.url.queryParameter("stb_type"))
    }

    @Test
    fun `a MAC the portal doesn't know is turned away`() {
        knownMac = false
        assertFalse(portal().signIn())
    }

    @Test
    fun `channels come with their genre and logo, and broken ones are left out`() {
        val channels = portal().channels()
        assertEquals(listOf("10", "11"), channels.map { it.id })
        assertEquals("US| NEWS", channels[0].genre)
        assertEquals("cnn.us", channels[0].xmltvId)
        assertEquals(server.url("/misc/10.png").toString(), channels[0].logo)
        assertEquals("SPORTS", channels[1].genre)
        assertEquals(null, channels[1].logo)
    }

    @Test
    fun `a channel's stream is asked for as it plays, signing in again when the token lapses`() {
        val p = portal()
        assertTrue(p.signIn())
        refuseTokens = 1
        val url = p.streamFor(Stalker.Link("itv", "10", "ffrt http://localhost/ch/10_"))
        assertEquals("http://cdn.example/play/10.ts?play_token=abc", url)
        assertEquals(2, handshakes)
        val create = seen.last { it.url.queryParameter("action") == "create_link" }
        assertEquals("ffrt http://localhost/ch/10_", create.url.queryParameter("cmd"))
        assertEquals("Bearer T2", create.headers["Authorization"])
    }

    @Test
    fun `a portal that never takes the token fails instead of asking forever`() {
        refuseTokens = 100
        assertFailsWith<IOException> { portal().streamFor(Stalker.Link("itv", "10", "ffrt http://localhost/ch/10_")) }
    }

    @Test
    fun `the guide is read programme by programme under the portal's channel ids`() {
        val got = ArrayList<Programme>()
        portal().guide(24) { got += it }
        assertEquals(listOf(Programme("10", 1_700_000_000_000L, 1_700_003_600_000L, "News", "Headlines")), got)
        assertEquals("24", seen.last().url.queryParameter("period"))
    }
}
