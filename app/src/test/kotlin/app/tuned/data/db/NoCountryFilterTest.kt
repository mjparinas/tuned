package app.tuned.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tuned.core.Regions
import app.tuned.core.Search
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Streams with no country have their own switch (Melvin, 2026-11-18: with every country off, 40,000 channels still
 * showed): turning it off hides them like any country, and Settings counts them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoCountryFilterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun streamsWithNoCountryHideWithTheirSwitch() = runTest {
        db.items().insertAll(listOf(channel(1, "ATP Finals 1", region = null), channel(2, "ATP Finals 2", region = "US")))

        suspend fun search(hidden: List<String>) =
            db.items().searchChannels("atp", Search.match("atp"), 0, hidden, adult = false, limit = 10).map { it.id }

        assertEquals(listOf(1L, 2L), search(emptyList()))
        assertEquals(listOf(1L), search(listOf("US")))
        assertEquals(listOf(2L), search(listOf(Regions.NONE)))
        assertEquals(emptyList(), search(listOf("US", Regions.NONE)))
    }

    @Test
    fun settingsCountStreamsWithNoCountry() = runTest {
        db.items().insertAll(listOf(channel(1, "A", region = null), channel(2, "B", region = null), channel(3, "C", region = "SE")))

        val counts =
            db
                .items()
                .regions()
                .first()
                .associate { it.region to it.count }

        assertEquals(mapOf(Regions.NONE to 2, "SE" to 1), counts)
    }

    private fun channel(
        id: Long,
        name: String,
        region: String?,
    ) = ItemEntity(
        id = id,
        kind = 0,
        name = name,
        searchName = Search.normalize(name),
        groupName = "EVENTS",
        region = region,
        adult = false,
        logo = null,
        url = "http://example.invalid/$id",
        tvgId = null,
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = id.toInt(),
    )
}
