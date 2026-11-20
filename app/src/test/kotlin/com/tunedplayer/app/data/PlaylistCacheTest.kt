package com.tunedplayer.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.ProgrammeEntity
import com.tunedplayer.app.data.db.TunedDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Switching back to a recent playlist shows it at once (Melvin, 2026-11-20: "do 4, 6, 7, 8"), from the copy kept when
 * it was switched away from, instead of downloading everything again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()
    private val dir = File(context.cacheDir, "playlist-cache-test").apply { deleteRecursively() }
    private val memo = File(dir, "memo/playlist.fingerprint")
    private var clock = 1_000_000L
    private val cache = PlaylistCache(db, File(dir, "copies"), memo) { clock }

    private val first = Credentials("http://one.example", "a", "p")
    private val second = Credentials("http://two.example", "b", "p")

    @After fun close() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun `a playlist switched away from comes back as it was`() = runTest {
        db.items().insertAll(listOf(channel(1, "Planet Wild"), channel(2, "News 24")))
        db.programmes().insertAll(listOf(show("ch1", "Night Owls", clock - 10_000, clock + 60_000)))
        memo.parentFile?.mkdirs()
        memo.writeText("abc")

        cache.save(first, PlaylistCache.Synced(playlist = 11, guide = 22))
        clear()
        memo.writeText("other")

        val synced = cache.restore(first)
        assertEquals(PlaylistCache.Synced(11, 22), synced)
        assertEquals(2, db.items().count())
        assertEquals(1, db.programmes().count())
        assertEquals("abc", memo.readText(), "the fingerprint travels with the copy, so an unchanged file isn't saved again")
        // The search index follows the restored channels through Room's triggers.
        val found =
            db.openHelper.readableDatabase.query("SELECT rowid FROM items_fts WHERE items_fts MATCH 'planet*'").use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
        assertEquals(listOf(1L), found)
    }

    @Test
    fun `programmes that have ended are left behind`() = runTest {
        db.programmes().insertAll(
            listOf(show("ch1", "Over", clock - 60_000, clock - 1), show("ch1", "Later", clock + 1, clock + 60_000)),
        )
        cache.save(first, PlaylistCache.Synced(1, 1))
        clear()
        cache.restore(first)
        assertEquals(1, db.programmes().count())
    }

    @Test
    fun `a playlist with no copy downloads as before`() = runTest {
        assertNull(cache.restore(second))
        assertFalse(cache.has(second))
    }

    @Test
    fun `only the newest copies are kept`() = runTest {
        db.items().insertAll(listOf(channel(1, "One")))
        val logins = (1..5).map { Credentials("http://p$it.example", "u", "p") }
        for (login in logins) {
            clock += 1_000
            cache.save(login, PlaylistCache.Synced(1, 1))
            File(dir, "copies/${PlaylistCache.key(login)}.db").setLastModified(clock)
        }
        assertEquals(listOf(false, false, true, true, true), logins.map { cache.has(it) })
    }

    @Test
    fun `removing a playlist or signing out deletes its copy`() = runTest {
        cache.save(first, PlaylistCache.Synced(1, 1))
        cache.save(second, PlaylistCache.Synced(1, 1))
        cache.forget(first)
        assertFalse(cache.has(first))
        assertTrue(cache.has(second))
        cache.clear()
        assertFalse(cache.has(second))
    }

    @Test
    fun `the file name never holds the address or the login`() {
        val key = PlaylistCache.key(Credentials("http://secret.example", "user", "pass"))
        assertFalse("secret" in key || "user" in key || "pass" in key)
        assertEquals(32, key.length)
    }

    private suspend fun clear() {
        db.items().deleteAll()
        db.programmes().deleteAll()
    }

    private fun channel(
        id: Long,
        name: String,
    ) = ItemEntity(
        id = id,
        kind = 0,
        name = name,
        searchName = Search.normalize(name),
        groupName = "News",
        region = null,
        adult = false,
        logo = null,
        url = "http://example.invalid/$id",
        tvgId = "ch$id",
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = id.toInt(),
    )

    private fun show(
        channel: String,
        title: String,
        start: Long,
        stop: Long,
    ) = ProgrammeEntity(channelId = channel, start = start, stop = stop, title = title, description = null)
}
