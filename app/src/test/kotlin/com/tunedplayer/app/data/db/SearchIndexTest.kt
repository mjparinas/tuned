package com.tunedplayer.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Search
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Search runs on the full-text index: word starts match, and the index follows every refresh. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchIndexTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private suspend fun movies(text: String) =
        db.items().searchMovies(Search.match(text), emptyList(), adult = false, limit = 10).map { it.id }

    @Test
    fun findsWordsByTheirStart() = runTest {
        db.items().insertAll(listOf(movie(1, "Top Gun: Maverick"), movie(2, "Gunpowder Milkshake"), movie(3, "Stop Making Sense")))

        assertEquals(listOf(1L), movies("top gu"))
        assertEquals(listOf(1L, 2L), movies("gun"))
        assertEquals(listOf(1L), movies("MAVERICK"))
        assertEquals(emptyList(), movies("top gun 2"))
    }

    @Test
    fun aRenamedStreamIsFoundByItsNewNameOnly() = runTest {
        db.items().insertAll(listOf(movie(1, "Old Title")))
        // A refresh writes the same id again with a new name.
        db.items().insertAll(listOf(movie(1, "New Title")))

        assertEquals(listOf(1L), movies("new"))
        assertEquals(emptyList(), movies("old"))
    }

    @Test
    fun aRemovedStreamLeavesTheIndex() = runTest {
        db.items().insertAll(listOf(movie(1, "Heat"), movie(2, "Heathers")))
        db.items().deleteIds(listOf(1L))

        assertEquals(listOf(2L), movies("heat"))
    }

    @Test
    fun theUpgradeIndexesTheCachedPlaylist() = runTest {
        db.items().insertAll(listOf(movie(7, "Top Gun: Maverick")))
        // Back to version 9's shape: the playlist is cached but has no index.
        val sql = db.openHelper.writableDatabase
        for (t in listOf("BEFORE_UPDATE", "BEFORE_DELETE", "AFTER_UPDATE", "AFTER_INSERT")) {
            sql.execSQL("DROP TRIGGER room_fts_content_sync_items_fts_$t")
        }
        sql.execSQL("DROP TABLE items_fts")
        sql.execSQL("DROP INDEX index_programmes_start")

        TunedDatabase.MIGRATION_9_10.migrate(sql)

        assertEquals(listOf(7L), movies("top gu"))
        // Its triggers keep the index in step from then on.
        db.items().insertAll(listOf(movie(7, "Heat")))
        assertEquals(emptyList(), movies("top"))
        assertEquals(listOf(7L), movies("heat"))
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
