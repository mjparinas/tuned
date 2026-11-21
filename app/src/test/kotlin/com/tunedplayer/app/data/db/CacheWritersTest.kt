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
import kotlin.test.assertFalse

/**
 * A refresh used to empty the tables and refill them inside one long transaction, and lists and search (which Room
 * pages inside transactions) showed nothing until it finished. These check that the cache stays readable and whole
 * while a new playlist or guide is being written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CacheWritersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun theOldGuideStaysSearchableUntilTheNewOneIsSwappedIn() = runTest {
        db.programmes().insertAll(listOf(programme("a", 0, "Top Gun"), programme("b", 0, "News")))
        val writer = GuideWriter(db)
        writer.begin()
        writer.add(listOf(programme("a", 10, "Top Gun: Maverick"), programme("b", 10, "Sport"), programme("c", 10, "Film")))

        assertEquals(2, db.programmes().count())
        assertEquals(listOf("Top Gun"), db.programmes().nowAndNext("a", 0).map { it.title })

        assertEquals(3, writer.commit())
        assertEquals(3, db.programmes().count())
        assertEquals(listOf("Top Gun: Maverick"), db.programmes().nowAndNext("a", 0).map { it.title })
        assertFalse(sideTableExists())
    }

    @Test
    fun anEmptyOrFailedGuideKeepsTheOldOne() = runTest {
        db.programmes().insertAll(listOf(programme("a", 0, "Top Gun")))
        val empty = GuideWriter(db)
        empty.begin()
        assertEquals(0, empty.commit())
        assertEquals(1, db.programmes().count())

        val failed = GuideWriter(db)
        failed.begin()
        failed.add(listOf(programme("a", 10, "Half a guide")))
        failed.discard()
        assertEquals(listOf("Top Gun"), db.programmes().nowAndNext("a", 0).map { it.title })
        assertFalse(sideTableExists())
    }

    @Test
    fun thePlaylistIsNeverEmptiedWhileItRefreshes() = runTest {
        db.items().insertAll(listOf(item(1, "Old one"), item(2, "Kept"), item(3, "Dropped")))
        val writer = PlaylistWriter(db)
        writer.begin()
        listOf(item(2, "Kept, renamed"), item(4, "New")).forEach { writer.accept(it.id) }
        assertFalse(writer.accept(2)) // listed twice
        writer.add(listOf(item(2, "Kept, renamed"), item(4, "New")))

        // Mid-refresh: the cache is exactly as it was, so lists and search carry on undisturbed.
        assertEquals(listOf(1L, 2L, 3L), db.items().allIds().sorted())
        assertEquals("Kept", db.items().get(2)?.name)
        assertEquals(listOf(3L), search("dropped"))

        assertEquals(2, writer.commit())
        assertEquals(listOf(2L, 4L), db.items().allIds().sorted())
        assertEquals("Kept, renamed", db.items().get(2)?.name)
        assertEquals(listOf(2L), search("renamed"))
        assertEquals(emptyList(), search("dropped"))
        assertFalse(tableExists("items_next"))
    }

    @Test
    fun aRefreshThatChangesEverythingLandsWhole() = runTest {
        db.items().insertAll((1L..5_000L).map { item(it, "Old $it") })
        val writer = PlaylistWriter(db)
        writer.begin()
        val next = (2L..5_001L).map { item(it, "New $it") }
        next.forEach { writer.accept(it.id) }
        next.chunked(1_000).forEach { writer.add(it) }

        assertEquals(5_000, writer.commit())
        assertEquals((2L..5_001L).toList(), db.items().allIds().sorted())
        assertEquals(emptyList(), search("old"))
        assertEquals(5_000, db.items().searchMovies("new*", emptyList(), adult = false, limit = 10_000).size)
    }

    @Test
    fun theFirstDownloadFillsTheListsAsItArrives() = runTest {
        val writer = PlaylistWriter(db)
        writer.begin()
        writer.accept(1)
        writer.add(listOf(item(1, "Early")))
        assertEquals(listOf(1L), db.items().allIds())
        assertEquals(1, writer.commit())
    }

    @Test
    fun theFirstDownloadShowsItsFirstStreamsAndBuildsTheRestInOneGo() = runTest {
        val schema = itemsSchema()
        val writer = PlaylistWriter(db)
        writer.begin()
        val all = (1L..3_000L).map { item(it, "Film $it") }
        all.forEach { writer.accept(it.id) }
        all.chunked(1_000).forEach { writer.add(it) }

        // While the rest is read, only the first streams are in the lists, so no batch makes every list start over.
        assertEquals(2_000, db.items().count())

        val steps = mutableListOf<Pair<Long, Long>>()
        assertEquals(3_000, writer.commit { done, total -> steps += done to total })
        assertEquals((1L..3_000L).toList(), db.items().allIds().sorted())
        assertEquals(listOf(2_999L), search("2999"))
        // The indexes and the search index come back exactly as Room made them.
        assertEquals(schema, itemsSchema())
        assertEquals(steps.last().second, steps.last().first)
        assertFalse(tableExists("items_next"))
    }

    @Test
    fun aRefreshThatReplacesMostOfTheCacheIsRebuiltWhole() = runTest {
        val schema = itemsSchema()
        db.items().insertAll((1L..12_000L).map { item(it, "Old $it") })
        val writer = PlaylistWriter(db)
        writer.begin()
        val next = (6_001L..18_000L).map { item(it, "New $it") }
        next.forEach { writer.accept(it.id) }
        next.chunked(1_000).forEach { writer.add(it) }
        assertEquals(12_000, db.items().count())

        assertEquals(12_000, writer.commit())
        assertEquals((6_001L..18_000L).toList(), db.items().allIds().sorted())
        assertEquals(emptyList(), search("old"))
        assertEquals(listOf(17_999L), search("17999"))
        assertEquals(schema, itemsSchema())
    }

    @Test
    fun aFirstDownloadOfOnePartLeavesTheOtherPart() = runTest {
        db.items().insertAll(listOf(item(1, "A film", kind = 1)))
        val writer = PlaylistWriter(db, scope = "kind = 0")
        writer.begin()
        val live = listOf(item(2, "A channel", kind = 0), item(3, "Another channel", kind = 0))
        live.forEach { writer.accept(it.id) }
        writer.add(live)
        assertEquals(2, writer.commit())
        assertEquals(listOf(1L, 2L, 3L), db.items().allIds().sorted())
        assertEquals(listOf(1L), search("film"))
    }

    @Test
    fun aFailedRefreshLeavesTheCache() = runTest {
        db.items().insertAll(listOf(item(1, "One")))
        val writer = PlaylistWriter(db)
        writer.begin()
        writer.accept(2)
        writer.add(listOf(item(2, "Two")))
        writer.discard()
        assertEquals(listOf(1L), db.items().allIds())
        assertFalse(tableExists("items_next"))
    }

    @Test
    fun anEmptyPlaylistChangesNothing() = runTest {
        db.items().insertAll(listOf(item(1, "One"), item(2, "Two")))
        val writer = PlaylistWriter(db)
        writer.begin()
        assertEquals(0, writer.commit())
        assertEquals(listOf(1L, 2L), db.items().allIds().sorted())
    }

    private fun itemsSchema() = db.openHelper.readableDatabase
        .query("SELECT type, name, sql FROM sqlite_master WHERE tbl_name = 'items' ORDER BY name")
        .use { c -> buildList { while (c.moveToNext()) add("${c.getString(0)} ${c.getString(1)} ${c.getString(2)}") } }

    private fun sideTableExists() = tableExists("programmes_next")

    private fun tableExists(name: String) = db.openHelper.readableDatabase
        .query("SELECT name FROM sqlite_master WHERE name = '$name'")
        .use { it.count > 0 }

    private suspend fun search(text: String) =
        db.items().searchMovies(Search.match(text), emptyList(), adult = false, limit = 100).map { it.id }

    private fun programme(
        channel: String,
        start: Long,
        title: String,
    ) = ProgrammeEntity(channelId = channel, start = start, stop = start + 100, title = title, description = null)

    private fun item(
        id: Long,
        name: String,
        kind: Int = 1,
    ) = ItemEntity(
        id = id,
        kind = kind,
        name = name,
        searchName = Search.normalize(name),
        groupName = "News",
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
