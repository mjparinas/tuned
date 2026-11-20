package com.tunedplayer.app.player

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.TunedDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * A channel its category hides (here a 24/7 channel with "Hide 24/7 channels" on) can still be opened from
 * Favourites or Search. The player used to fall back to the category's first channel and play something else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerQueueTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun `a hidden channel opened directly is the one that plays`() = runTest {
        val channels = listOf(channel(1, "Sport 1"), channel(2, "24/7 Simpsons"), channel(3, "Sport 2"))
        db.items().insertAll(channels)

        val queue = playerSiblings(db, AppSettings(hide247 = true), channels[1])

        assertEquals(listOf(2L, 1L, 3L), queue.map { it.id })
    }

    @Test
    fun `a visible channel keeps the category's order`() = runTest {
        val channels = listOf(channel(1, "Sport 1"), channel(2, "Sport 2"), channel(3, "Sport 3"))
        db.items().insertAll(channels)

        assertEquals(listOf(1L, 2L, 3L), playerSiblings(db, AppSettings(), channels[1]).map { it.id })
    }

    private fun channel(
        id: Long,
        name: String,
    ) = ItemEntity(
        id = id,
        kind = 0,
        name = name,
        searchName = Search.normalize(name),
        groupName = "Sport",
        region = null,
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
