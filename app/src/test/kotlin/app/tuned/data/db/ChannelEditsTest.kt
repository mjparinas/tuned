package app.tuned.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tuned.core.Search
import app.tuned.data.ChannelEditor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Renamed channels and their own logos (Melvin, 2026-11-19: "do the rename if people want it"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChannelEditsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()
    private val editor = ChannelEditor(db)

    @After
    fun close() = db.close()

    @Test
    fun aRenameShowsAtOnceAndSearchFindsEitherName() = runTest {
        db.items().insertAll(listOf(channel(1, "US| ESPN FHD", "a.png")))

        editor.set(1, "ESPN", null)

        assertEquals("ESPN", db.items().get(1)?.name)
        assertEquals("a.png", db.items().get(1)?.logo)
        assertEquals(listOf(1L), search("espn"))
        assertEquals(listOf(1L), search("fhd"))
    }

    @Test
    fun aRefreshKeepsTheRenameAndLogoAndNotesTheProvidersNewName() = runTest {
        db.items().insertAll(listOf(channel(1, "ESPN HD", "a.png"), channel(2, "News", null)))
        editor.set(1, "Sport", "mine.png")

        val writer = PlaylistWriter(db)
        writer.begin()
        val next = listOf(channel(1, "ESPN FHD", "b.png"), channel(2, "News 24", null))
        next.forEach { writer.accept(it.id) }
        writer.add(next)
        writer.commit()

        assertEquals("Sport", db.items().get(1)?.name)
        assertEquals("mine.png", db.items().get(1)?.logo)
        assertEquals("ESPN FHD", db.channelEdits().get(1)?.originalName)
        assertEquals("b.png", db.channelEdits().get(1)?.originalLogo)
        assertEquals("News 24", db.items().get(2)?.name)
    }

    @Test
    fun theOriginalsComeBackAndTheEditGoes() = runTest {
        db.items().insertAll(listOf(channel(1, "ESPN HD", "a.png")))
        editor.set(1, "Sport", "mine.png")

        editor.set(1, null, "mine.png")
        assertEquals("ESPN HD", db.items().get(1)?.name)
        assertEquals("mine.png", db.items().get(1)?.logo)

        editor.set(1, null, null)
        assertEquals("a.png", db.items().get(1)?.logo)
        assertNull(db.channelEdits().get(1))
        assertEquals(emptyList(), search("sport"))
    }

    @Test
    fun theProvidersOwnNameIsNotAnEdit() = runTest {
        db.items().insertAll(listOf(channel(1, "ESPN HD", "a.png")))
        editor.set(1, " ESPN HD ", "a.png")
        assertNull(db.channelEdits().get(1))
    }

    private fun search(text: String): List<Long> = db.openHelper.readableDatabase
        .query("SELECT docid FROM items_fts WHERE items_fts MATCH ?", arrayOf<Any>(Search.match(text)))
        .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    private fun channel(
        id: Long,
        name: String,
        logo: String?,
    ) = ItemEntity(
        id = id,
        kind = 0,
        name = name,
        searchName = Search.normalize(name),
        groupName = "Sports",
        region = null,
        adult = false,
        logo = logo,
        url = "http://example.invalid/$id",
        tvgId = null,
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = id.toInt(),
    )
}
