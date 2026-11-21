package com.tunedplayer.app.data.db

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Search
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cache helpers that write SQL straight through `openHelper` never run it on the screen thread, wherever they are
 * called from (Melvin, 2026-11-20: 2.3.0 froze Home for 5 seconds and more rebuilding the Shows table there). Each test
 * calls a helper from the screen thread, as a screen's scope does, and fails on any statement the screen thread ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OffMainTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val onMain = Collections.synchronizedList(mutableListOf<String>())
    private val db =
        Room
            .inMemoryDatabaseBuilder(context, TunedDatabase::class.java)
            // A direct executor, so the callback runs on the thread that ran the statement.
            .setQueryCallback({ sql, _ -> if (Looper.myLooper() == Looper.getMainLooper()) onMain += sql }, Executor { it.run() })
            .build()

    @After
    fun close() = db.close()

    private fun fromScreenThread(block: suspend () -> Unit) {
        assertEquals(Looper.getMainLooper(), Looper.myLooper(), "the test runs on the screen thread")
        runBlocking { block() }
        assertTrue(onMain.isEmpty(), "SQL ran on the screen thread: ${onMain.distinct().joinToString(" / ")}")
    }

    private suspend fun seed() {
        db.items().insertAll(
            (1L..50L).map { id ->
                val name = "EN - Film $id (2001)"
                val film =
                    ItemEntity(
                        id = id,
                        kind = if (id % 2 == 0L) 2 else 1,
                        name = name,
                        searchName = Search.normalize(name),
                        groupName = "Films",
                        region = null,
                        adult = false,
                        logo = null,
                        url = "http://example.invalid/movie/u/p/$id.mp4",
                        tvgId = null,
                        showKey = null,
                        showName = null,
                        season = null,
                        episode = null,
                        sortOrder = id.toInt(),
                    )
                if (film.kind == 2) {
                    film.copy(showKey = "show ${id % 5}", showName = "Show ${id % 5}", season = 1, episode = id.toInt())
                } else {
                    film
                }
            },
        )
        db.programmes().insertAll(
            (1L..20L).map { ProgrammeEntity(channelId = "ch$it", start = it, stop = it + 100, title = "Hockey $it", description = null) },
        )
        onMain.clear()
    }

    @Test
    fun sortFieldsAndTheShowsTable() {
        runBlocking { seed() }
        fromScreenThread { SortFields.fillIfMissing(db) }
        fromScreenThread { ShowGroups.rebuild(db) }
        runBlocking { assertTrue(db.openHelperCount("SELECT COUNT(*) FROM show_groups") > 0) }
    }

    @Test
    fun channelNumbersAndTheGuideIndex() {
        runBlocking { seed() }
        fromScreenThread { ChannelNumbers.fillIfMissing(db) }
        fromScreenThread { GuideSearchIndex.fillIfMissing(db) }
        fromScreenThread { GuideSearchIndex.search(db, "hockey", 0, emptyList(), adult = false, limit = 10) }
        fromScreenThread { Upkeep.optimize(db) }
    }

    private suspend fun TunedDatabase.openHelperCount(query: String): Long = offMain {
        openHelper.readableDatabase.query(query).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 }
    }
}
