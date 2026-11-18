package app.tuned.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tuned.data.db.TunedDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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

/** A season's episode names and screenshots come from TMDB once, then from the device, and survive going offline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SeasonMetadataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()
    private val server = MockWebServer().apply { start() }

    // Every TMDB link goes to the test server instead.
    private val http =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val url =
                    chain
                        .request()
                        .url
                        .newBuilder()
                        .scheme("http")
                        .host(server.hostName)
                        .port(server.port)
                        .build()
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .url(url)
                        .build(),
                )
            }.build()
    private val repo = repo("abc")

    private fun repo(key: String) = MetadataRepository(http, key, db.metadata(), CoroutineScope(SupervisorJob()), online = { true })

    @After
    fun close() {
        db.close()
        server.close()
    }

    @Test
    fun aSeasonIsAskedForOnceAndThenComesFromTheDevice() = runTest {
        server.enqueue(MockResponse.Builder().body(SEASON).build())

        val first = repo.season(456, 4)
        assertEquals("Kamp Krusty", first?.get(1)?.name)
        assertEquals("https://image.tmdb.org/t/p/w300/kamp.jpg", first?.get(1)?.still)
        assertEquals("22m", first?.get(1)?.runtime)
        assertNull(first?.get(2)?.still)
        val asked = server.takeRequest().url
        assertEquals("/3/tv/456/season/4", asked.encodedPath)
        assertEquals("abc", asked.queryParameter("api_key"))

        // Fresh in the cache: no second request (the server has nothing left to answer with).
        assertEquals(first, repo.season(456, 4))
        assertEquals(first, repo.cachedSeason(456, 4))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aSeasonTmdbDoesNotHaveIsRememberedAsMissing() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        assertNull(repo.season(456, 30))
        assertNull(repo.season(456, 30))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anEpisodeIsNamedFromTheShowsNameAndThenFromTheDevice() = runTest {
        assertNull(repo.cachedEpisode("EN - The Simpsons (1989)", 4, 1))
        server.enqueue(MockResponse.Builder().body("""{"results":[{"id":456,"name":"The Simpsons"}]}""").build())
        server.enqueue(MockResponse.Builder().body(SEASON).build())

        assertEquals("Kamp Krusty", repo.episode("EN - The Simpsons (1989)", 4, 1)?.name)
        assertEquals("/3/search/tv", server.takeRequest().url.encodedPath)
        assertEquals("/3/tv/456/season/4", server.takeRequest().url.encodedPath)

        // Every episode of that season is known on the device now, with no further requests.
        assertEquals("A Streetcar Named Marge", repo.cachedEpisode("EN - The Simpsons (1989)", 4, 2)?.name)
        assertEquals("Kamp Krusty", repo.episode("EN - The Simpsons (1989)", 4, 1)?.name)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun withoutAKeyNothingIsAsked() = runTest {
        assertNull(repo("").season(456, 4))
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val SEASON =
            """{"episodes":[
            {"episode_number":1,"name":"Kamp Krusty","overview":"Bart and Lisa go to camp.","runtime":22,"still_path":"/kamp.jpg"},
            {"episode_number":2,"name":"A Streetcar Named Marge","overview":"","still_path":null,"extra":1}
            ]}"""
    }
}
