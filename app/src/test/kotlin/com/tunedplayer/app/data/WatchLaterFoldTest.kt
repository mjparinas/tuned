package com.tunedplayer.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.data.db.FavouriteEntity
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.data.db.WatchLaterEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Watch later became part of Favourites (Melvin, 2026-11-27): what was saved moves over, per profile, and nothing is lost. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchLaterFoldTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun filmsAndShowsMoveIntoEachProfilesFavourites() = runTest {
        val sync = db.sync()
        sync.insertWatchLater(
            listOf(
                WatchLaterEntity("m:9", 9, null, "Film", null, addedAt = 10, profileId = 1),
                WatchLaterEntity("s:bluey", null, "bluey", "Bluey", null, addedAt = 20, profileId = 1),
                WatchLaterEntity("m:9", 9, null, "Film", null, addedAt = 30, profileId = 2),
            ),
        )
        // Already a favourite on profile 2: it keeps when it was favourited.
        sync.insertFavourites(listOf(FavouriteEntity(9, addedAt = 5, position = 1, profileId = 2)))

        assertEquals(3, WatchLaterFold.fold(db))

        val favourites = sync.favourites().map { Triple(it.profileId, it.itemId, it.addedAt) }.toSet()
        assertEquals(
            setOf(Triple(1L, 9L, 10L), Triple(1L, FavouriteShows.id("bluey"), 20L), Triple(2L, 9L, 5L)),
            favourites,
        )
        assertEquals(emptyList(), sync.watchLater())
        // Nothing left: a second run, as after the next backup, changes nothing.
        assertEquals(0, WatchLaterFold.fold(db))
        assertEquals(3, sync.favourites().size)
    }
}
