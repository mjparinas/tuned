package com.tunedplayer.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Search
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Signing out empties the database as on a fresh install, except the downloads when the person keeps them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseResetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private suspend fun fill() {
        db.items().insertAll(listOf(movie(1, "Heat"), movie(2, "Ronin")))
        db.library().addFavourite(FavouriteEntity(1, addedAt = 1))
        db.library().saveProgress(HistoryEntity(2, positionMs = 1_000, durationMs = 10_000, watchedAt = 2))
        db.profiles().insert(ProfileEntity(id = Profiles.FIRST, name = "", colour = 0, kids = false, pinHash = null, createdAt = 0))
        val kids = db.profiles().insert(ProfileEntity(name = "Kids", colour = 1, kids = true, pinHash = "hash", createdAt = 3))
        db.profiles().setCurrent(CurrentProfileEntity(profileId = kids))
        db.lists().create(ListEntity(id = 7, name = "Weekend", position = 0, createdAt = 4))
        db.downloads().upsert(DownloadEntity(1, "Heat", "http://h/user/pass/1.mp4", "/x/1.mp4", state = DownloadEntity.DONE, addedAt = 5))
        db.downloads().upsert(DownloadEntity(2, "Ronin", "http://h/user/pass/2.mp4", "/x/2.mp4", addedAt = 6))
    }

    @Test
    fun everythingGoesAndOneEmptyProfileIsInUse() = runTest {
        fill()

        db.wipe()

        assertEquals(emptyList(), db.items().searchMovies(Search.match("heat"), emptyList(), adult = false, limit = 10))
        assertEquals(emptyList(), db.library().favouriteIds().first())
        assertNull(db.library().progress(2))
        assertEquals(emptyList(), db.lists().allNow())
        assertEquals(0, db.downloads().count())
        assertEquals(listOf(Profiles.FIRST), db.profiles().allNow().map { it.id })
        val current = db.profiles().currentNow()
        assertEquals(Profiles.FIRST, current?.id)
        assertNull(current?.pinHash)
    }

    @Test
    fun keptDownloadsStayWithoutTheirLinks() = runTest {
        fill()

        db.downloads().deleteUnfinished()
        db.downloads().forgetLinks()
        db.wipe(keep = setOf("downloads"))

        val kept = db.downloads().all().first()
        assertEquals(listOf(1L), kept.map { it.itemId })
        assertEquals("", kept.single().url)
        assertEquals(emptyList(), db.library().favouriteIds().first())
        // The same provider signing back in brings the same ids back, and the download joins its film again.
        db.items().insertAll(listOf(movie(1, "Heat")))
        assertEquals(listOf(1L), db.downloads().done(listOf(1L)).map { it.itemId })
    }

    @Test
    fun aSecondWipeIsHarmless() = runTest {
        fill()
        db.wipe()
        db.wipe()

        assertEquals(1, db.profiles().allNow().size)
    }

    private fun movie(
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
        url = "http://example.invalid/$id",
        tvgId = null,
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = id.toInt(),
    )
}
