package app.tuned.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

        // Mid-refresh: everything from before is still there, alongside what has arrived.
        assertEquals(listOf(1L, 2L, 3L, 4L), db.items().allIds().sorted())

        assertEquals(2, writer.commit())
        assertEquals(listOf(2L, 4L), db.items().allIds().sorted())
        assertEquals("Kept, renamed", db.items().get(2)?.name)
    }

    @Test
    fun anEmptyPlaylistChangesNothing() = runTest {
        db.items().insertAll(listOf(item(1, "One"), item(2, "Two")))
        val writer = PlaylistWriter(db)
        writer.begin()
        assertEquals(0, writer.commit())
        assertEquals(listOf(1L, 2L), db.items().allIds().sorted())
    }

    private fun sideTableExists() = db.openHelper.readableDatabase
        .query("SELECT name FROM sqlite_master WHERE name = 'programmes_next'")
        .use { it.count > 0 }

    private fun programme(
        channel: String,
        start: Long,
        title: String,
    ) = ProgrammeEntity(channelId = channel, start = start, stop = start + 100, title = title, description = null)

    private fun item(
        id: Long,
        name: String,
    ) = ItemEntity(
        id = id,
        kind = 0,
        name = name,
        searchName = name.lowercase(),
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
