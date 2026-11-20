package com.tunedplayer.app.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.ChannelQuality
import com.tunedplayer.app.core.LiveTopic
import com.tunedplayer.app.core.ProgrammeGenre
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.ui.browse.LiveQuick
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Live TV's quality, On now, Has guide, Catch-up and Recently watched filters, alone and with the others. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveMoreFiltersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private val now = 10_000L

    private val channels =
        listOf(
            channel(1, "TSN 1 FHD", "CA| Sports", "tsn1"),
            channel(2, "CNN HD", "US| News", "cnn"),
            channel(3, "Sky Sports Main Event UHD", "UK| Sports", "skyme"),
            channel(4, "TF1 SD", "FR| General", null),
            channel(5, "Channel 4", "UK| General", "c4"),
            channel(6, "Movie Time", "US| Movies", "mt"),
        )

    private suspend fun setUp() {
        db.items().insertAll(channels)
        db.programmes().insertAll(
            listOf(
                programme("tsn1", "Leafs vs Canadiens", ProgrammeGenre.SPORTS),
                programme("cnn", "The Situation Room", ProgrammeGenre.NEWS),
                programme("mt", "The Long Night", ProgrammeGenre.MOVIES),
                // Sky's guide ended before now: no guide from now on.
                ProgrammeEntity(channelId = "skyme", start = 0, stop = now - 1, title = "Earlier", description = null),
                programme("c4", "Countdown", null),
            ),
        )
    }

    private suspend fun shown(quick: LiveQuick): List<Long> {
        val groups = channels.map { it.groupName }.distinct()
        val source =
            db.items().channels(
                now,
                emptyList(),
                false,
                null,
                "",
                "",
                quick.topicQuery,
                quick.keys(groups),
                quick.qualityQuery,
                quick.genreCode,
            )
        val page = source.load(PagingSource.LoadParams.Refresh(null, 500, false)) as PagingSource.LoadResult.Page
        return page.data.map { it.id }
    }

    @Test
    fun qualityIsReadFromTheName() = runTest {
        setUp()
        assertEquals(listOf(3L), shown(LiveQuick(quality = ChannelQuality.UHD)))
        assertEquals(listOf(1L), shown(LiveQuick(quality = ChannelQuality.FHD)))
        // "FHD" and "UHD" aren't HD, and "Channel 4" isn't 4K.
        assertEquals(listOf(2L), shown(LiveQuick(quality = ChannelQuality.HD)))
        assertEquals(listOf(4L), shown(LiveQuick(quality = ChannelQuality.SD)))
    }

    @Test
    fun onNowKeepsChannelsShowingThatKindNow() = runTest {
        setUp()
        assertEquals(listOf(1L), shown(LiveQuick(onNow = ProgrammeGenre.SPORTS)))
        assertEquals(listOf(6L), shown(LiveQuick(onNow = ProgrammeGenre.MOVIES)))
        assertEquals(emptyList(), shown(LiveQuick(onNow = ProgrammeGenre.KIDS)))
    }

    @Test
    fun hasGuideKeepsChannelsWithProgrammesFromNowOn() = runTest {
        setUp()
        assertEquals(listOf(1L, 2L, 5L, 6L), shown(LiveQuick(withGuide = true)))
    }

    @Test
    fun catchUpKeepsChannelsThatKeepRecordings() = runTest {
        setUp()
        db.catchUp().insertItems(listOf(CatchUpItemEntity(2), CatchUpItemEntity(5)))
        assertEquals(listOf(2L, 5L), shown(LiveQuick(catchUp = true)))
        // Forgetting catch-up forgets the filter's copy with it.
        db.catchUp().deleteAll()
        assertEquals(emptyList(), shown(LiveQuick(catchUp = true)))
    }

    @Test
    fun recentlyWatchedKeepsThisProfilesChannels() = runTest {
        setUp()
        db.library().saveProgress(HistoryEntity(4, positionMs = 0, durationMs = 0, watchedAt = 1))
        db.library().saveProgress(HistoryEntity(1, positionMs = 0, durationMs = 0, watchedAt = 2))
        assertEquals(listOf(1L, 4L), shown(LiveQuick(watched = true)))
    }

    @Test
    fun theFiltersNarrowTogether() = runTest {
        setUp()
        assertEquals(listOf(1L, 3L), shown(LiveQuick(topic = LiveTopic.SPORTS)))
        assertEquals(listOf(1L), shown(LiveQuick(topic = LiveTopic.SPORTS, withGuide = true)))
        assertEquals(emptyList(), shown(LiveQuick(topic = LiveTopic.SPORTS, quality = ChannelQuality.HD)))
        assertEquals(listOf(2L), shown(LiveQuick(quality = ChannelQuality.HD, onNow = ProgrammeGenre.NEWS, withGuide = true)))
    }

    @Test
    fun theSheetOffersOnlyWhatFindsSomething() = runTest {
        setUp()
        val dao = db.liveFilters()
        assertTrue(dao.hasNamed(ChannelQuality.UHD.fullText))
        assertTrue(dao.hasGuide(now))
        assertFalse(dao.hasGuide(now + 1_000_000))
        assertFalse(dao.hasCatchUp())
        assertFalse(dao.hasWatchedChannels())
        assertEquals(setOf(ProgrammeGenre.SPORTS.code, ProgrammeGenre.NEWS.code, ProgrammeGenre.MOVIES.code), dao.genresOnNow(now).toSet())
    }

    @Test
    fun theUpgradeMakesTheSameTablesAsAFreshInstall() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(null)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(20) {
                            override fun onCreate(db: SupportSQLiteDatabase) = Unit

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        val old = helper.writableDatabase
        old.execSQL(
            "CREATE TABLE IF NOT EXISTS `programmes` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `channelId` TEXT NOT NULL, " +
                "`start` INTEGER NOT NULL, `stop` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT)",
        )
        TunedDatabase.MIGRATION_20_21.migrate(old)
        val fresh = db.openHelper.writableDatabase
        assertEquals(shape(fresh, "programmes"), shape(old, "programmes"))
        assertEquals(shape(fresh, "catchup_items"), shape(old, "catchup_items"))
        helper.close()
    }

    private fun shape(
        db: SupportSQLiteDatabase,
        table: String,
    ): List<String> {
        val columns = mutableListOf<String>()
        db.query("SELECT name, type, `notnull`, dflt_value, pk FROM pragma_table_info('$table')").use { c ->
            while (c.moveToNext()) columns += (0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" }
        }
        return columns.sorted()
    }

    private fun programme(
        channel: String,
        title: String,
        genre: ProgrammeGenre?,
    ) = ProgrammeEntity(
        channelId = channel,
        start = now - 600,
        stop = now + 600,
        title = title,
        description = null,
        genre =
            genre?.code ?: 0,
    )

    private fun channel(
        id: Long,
        name: String,
        group: String,
        tvgId: String?,
    ) = ItemEntity(
        id = id,
        kind = 0,
        name = name,
        searchName = Search.normalize(name),
        groupName = group,
        region = null,
        adult = false,
        logo = null,
        url = "http://example.invalid/$id",
        tvgId = tvgId,
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = id.toInt(),
    )
}
