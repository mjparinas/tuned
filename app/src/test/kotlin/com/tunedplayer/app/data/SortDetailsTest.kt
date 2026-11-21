package com.tunedplayer.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.core.TitleSort
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.TunedDatabase
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
import kotlin.test.assertEquals

/**
 * A cache from before the sorts has no times or ratings until a refresh reads them, so in 2.2.0 Recently added and Top
 * rated kept the playlist's order (Melvin, 2026-11-20: "sorting on the movies page or the tv page doesn't do anything").
 * Choosing a sort reads them at once, from the API's film list, and only while none are known.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SortDetailsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun aSortFillsMissingTimesAndRatingsFromTheApi() = runTest {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val films = """[{"stream_id":"7","name":"Dune","added":"1700000000","rating":"8.1"}]"""
                    val body = if (request.url.queryParameter("action") == "get_vod_streams") films else "[]"
                    return MockResponse.Builder().body(body).build()
                }
            }
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            db.items().insertAll(listOf(film(1, "Dune", "$base/movie/u/p/7.mp4")))
            val login = Credentials(base, "u", "p")
            val details = SortDetails(db, OkHttpClient(), { login }, SettingsRepository(context))

            details.fillIfMissing()
            val dune = db.items().get(1)
            assertEquals(1_700_000_000L, dune?.added)
            assertEquals(8.1, dune?.rating)

            // Once anything is known, refreshes keep it current: no more calls from here.
            val calls = server.requestCount
            details.fillIfMissing()
            assertEquals(calls, server.requestCount)
        } finally {
            server.close()
        }
    }

    private fun film(
        id: Long,
        name: String,
        url: String,
    ) = ItemEntity(
        id = id,
        kind = 1,
        name = name,
        searchName = Search.normalize(name),
        groupName = "Films",
        region = null,
        adult = false,
        logo = null,
        url = url,
        tvgId = null,
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = id.toInt(),
        added = 0,
        year = null,
        rating = null,
        sortTitle = TitleSort.title(name),
    )
}
