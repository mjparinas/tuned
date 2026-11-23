package com.tunedplayer.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.core.TitleSort
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.TunedDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Top rated looks up TMDB ratings in the background: a 429 waits and resumes the same titles rather than giving up
 * (Melvin, 2026-11-23), while a stretch of plain failures still stops so an offline fill doesn't spin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TitleRatingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()
    private val server = MockWebServer().apply { start() }
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
    private val metadata = MetadataRepository(http, "key", db.metadata(), CoroutineScope(SupervisorJob()), online = { true })
    private val ratings = TitleRatings(db, metadata)

    @After
    fun close() {
        db.close()
        server.close()
    }

    @Test
    fun aRateLimitWaitsAndResumesInsteadOfGivingUp() = runTest {
        db.items().insertAll(listOf(film(1, "Dune"), film(2, "Heat")))
        val hits = AtomicInteger(0)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // First two searches are 429s (one whole chunk of failures), then answers.
                    if (hits.getAndIncrement() < 2) {
                        return MockResponse.Builder().code(429).build()
                    }
                    return MockResponse.Builder().body(hit(request.url.encodedPath)).build()
                }
            }

        ratings.fill(tv = false)

        assertTrue(metadata.answered("Dune", tv = false))
        assertTrue(metadata.answered("Heat", tv = false))
        assertTrue(hits.get() > 2, "retried after the 429s")
    }

    @Test
    fun plainFailuresStillStopAfterAFewTries() = runTest {
        db.items().insertAll(listOf(film(1, "Dune"), film(2, "Heat")))
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(500).build()
            }

        ratings.fill(tv = false)

        assertFalse(metadata.answered("Dune", tv = false))
        assertFalse(metadata.answered("Heat", tv = false))
        // Three failed chunks × six lookups, plus retries of the same chunk: bounded, not the whole library forever.
        assertTrue(server.requestCount in 6..40, "gave up after a few offline tries: ${server.requestCount}")
    }

    @Test
    fun askReportsRateLimitedWithoutCaching() = runTest {
        server.enqueue(MockResponse.Builder().code(429).build())
        assertEquals(RatingAsk.RATE_LIMITED, metadata.ask("Dune", tv = false))
        assertFalse(metadata.answered("Dune", tv = false))
        // summary still returns null rather than throwing, so posters stay quiet.
        server.enqueue(MockResponse.Builder().code(429).build())
        assertEquals(null, metadata.summary("Dune", tv = false))
    }

    private fun hit(path: String): String {
        val title = if (path.contains("/tv")) "\"name\":\"Heat\"" else "\"title\":\"Dune\""
        return """{"results":[{"id":1,$title,"vote_average":8.1,"vote_count":100}]}"""
    }

    private fun film(
        id: Long,
        name: String,
    ) = ItemEntity(
        id = id,
        kind = 1,
        name = name,
        searchName = Search.normalize(name),
        groupName = "Films",
        region = null,
        adult = false,
        logo = null,
        url = "http://example.invalid/movie/u/p/$id.mp4",
        tvgId = null,
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = id.toInt(),
        added = 0,
        year = TitleSort.yearInName(name),
        rating = null,
        sortTitle = TitleSort.title(name),
    )
}
