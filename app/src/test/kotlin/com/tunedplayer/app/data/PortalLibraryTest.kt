package com.tunedplayer.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Episodes
import com.tunedplayer.app.core.Stalker
import com.tunedplayer.app.data.db.TunedDatabase
import kotlinx.coroutines.test.runTest
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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A MAC portal's films and shows (Melvin, 2026-11-20: "add films and shows for mac portals"). They used to be left
 * out, so a portal had Live TV only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PortalLibraryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()
    private val dir = File(context.cacheDir, "portal-library-test").apply { deleteRecursively() }
    private val server = MockWebServer()

    /** The films the portal lists; a refresh can change them. */
    @Volatile private var films = listOf("Heat", "Ronin")

    @Volatile private var libraryDown = false

    @Before fun start() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val q = request.url
                    val type = q.queryParameter("type")
                    return when (q.queryParameter("action")) {
                        "handshake" -> {
                            json("""{"js":{"token":"T"}}""")
                        }

                        "get_profile" -> {
                            json("""{"js":{"blocked":"0"}}""")
                        }

                        "get_genres" -> {
                            json("""{"js":[{"id":"1","title":"US| NEWS"}]}""")
                        }

                        "get_all_channels" -> {
                            json("""{"js":{"data":[{"id":"10","name":"CNN","cmd":"ffmpeg http://cdn.example/10.ts","tv_genre_id":"1"}]}}""")
                        }

                        "get_categories" -> {
                            if (libraryDown) {
                                MockResponse.Builder().code(500).build()
                            } else if (type == "vod") {
                                json("""{"js":[{"id":"5","title":"EN | ACTION"}]}""")
                            } else {
                                json("""{"js":[{"id":"8","title":"EN | DRAMA"}]}""")
                            }
                        }

                        "get_ordered_list" -> {
                            list(request)
                        }

                        else -> {
                            MockResponse.Builder().code(404).build()
                        }
                    }
                }
            }
        server.start()
    }

    @After fun stop() {
        server.close()
        db.close()
    }

    private fun list(request: RecordedRequest): MockResponse {
        val q = request.url
        if (q.queryParameter("movie_id") == "77") {
            return json(
                """{"js":{"data":[{"id":"77:1","name":"Season 1","cmd":"c1","series":[1,2]},""" +
                    """{"id":"77:2","name":"Season 2","cmd":"c2","series":[1]}]}}""",
            )
        }
        // Everything fits on the first page.
        val data =
            if (q.queryParameter("type") == "vod") {
                films.mapIndexed { i, name -> """{"id":"${i + 1}","name":"$name","cmd":"/media/${i + 1}.mpg"}""" }
            } else {
                listOf("""{"id":"77","name":"Harbour Town","cmd":""}""")
            }
        return json("""{"js":{"total_items":${data.size},"max_page_items":14,"data":[${data.joinToString(",")}]}}""")
    }

    private fun json(body: String) = MockResponse
        .Builder()
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun sync(): SyncRepository {
        val login = Credentials.stalker(server.url("/c/").toString().trimEnd('/'), "00:1A:79:AB:CD:EF")
        return SyncRepository(db, OkHttpClient(), { login }, SettingsRepository(context), dir)
    }

    private fun rows(kind: Int) = db.openHelper.readableDatabase
        .query("SELECT name FROM items WHERE kind = $kind ORDER BY sortOrder")
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test
    fun filmsAndShowsArriveAfterLiveTv() = runTest {
        sync().refreshAll()

        assertEquals(listOf("CNN"), rows(0))
        assertEquals(listOf("Heat", "Ronin"), rows(1))
        assertEquals(listOf("Harbour Town"), rows(2))
        val film =
            db.openHelper.readableDatabase.query("SELECT url FROM items WHERE kind = 1 LIMIT 1").use {
                it.moveToFirst()
                it.getString(0)
            }
        assertEquals(Stalker.VOD, Stalker.parse(film)?.type)
    }

    @Test
    fun openingAShowFetchesItsSeasonsAndARefreshKeepsThem() = runTest {
        val sync = sync()
        sync.refreshAll()
        val key = Episodes.showKey("Harbour Town")
        sync.loadShow(key)

        val episodes = db.items().episodesNow(key)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), episodes.map { it.season to it.episode })
        assertEquals("2", Stalker.parse(episodes[1].url)?.series)

        sync.refreshAll()
        assertEquals(3, db.items().episodesNow(key).size)
    }

    @Test
    fun aLiveRefreshKeepsTheFilmsAndALibraryThatFailsKeepsThemToo() = runTest {
        val sync = sync()
        sync.refreshAll()
        films = listOf("Heat", "Ronin", "Thief")
        sync.refreshAll()
        assertEquals(listOf("Heat", "Ronin", "Thief"), rows(1))

        libraryDown = true
        sync.refreshAll()
        assertEquals(listOf("CNN"), rows(0))
        assertEquals(listOf("Heat", "Ronin", "Thief"), rows(1))
        assertTrue(rows(2).isNotEmpty())
    }
}
