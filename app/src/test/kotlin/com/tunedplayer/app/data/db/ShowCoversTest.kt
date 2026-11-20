package com.tunedplayer.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * A show keeps its own cover once opened, rather than one of its episodes' screenshots (Melvin, 2026-11-19: shows
 * should use "the shows main cover/poster").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShowCoversTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private fun logos(): List<String?> = db.openHelper.readableDatabase.query("SELECT logo FROM show_groups").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    @Test
    fun anOpenedShowUsesItsCoverNotAnEpisodeScreenshot() = runTest {
        db.items().insertAll(listOf(episode(1, "still-a"), episode(2, "still-z")))
        ShowGroups.rebuild(db)
        // Without a cover, the episodes' pictures stand in: the screenshot the page used to show.
        assertEquals(listOf<String?>("still-z"), logos())

        db.shows().saveCovers(listOf(ShowCoverEntity("harbour town", "cover")))
        ShowGroups.rebuild(db)
        assertEquals(listOf<String?>("cover"), logos())

        // Switching playlists forgets them with the shows.
        db.shows().deleteCovers()
        ShowGroups.rebuild(db)
        assertEquals(listOf<String?>("still-z"), logos())
    }

    @Test
    fun theUpgradeMakesTheSameTableAsAFreshInstall() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(null)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(19) {
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
        TunedDatabase.MIGRATION_19_20.migrate(old)
        val fresh = db.openHelper.writableDatabase
        assertEquals(shape(fresh), shape(old))
        helper.close()
    }

    private fun shape(db: SupportSQLiteDatabase): List<String> {
        val columns = mutableListOf<String>()
        db.query("SELECT name, type, `notnull`, dflt_value, pk FROM pragma_table_info('show_covers')").use { c ->
            while (c.moveToNext()) columns += (0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" }
        }
        return columns.sorted()
    }

    private fun episode(
        id: Long,
        logo: String,
    ) = ItemEntity(
        id = id,
        kind = 2,
        name = "Harbour Town S01E0$id",
        searchName = "harbour town s01e0$id",
        groupName = "Drama",
        region = null,
        adult = false,
        logo = logo,
        url = "http://example.invalid/$id",
        tvgId = null,
        showKey = "harbour town",
        showName = "Harbour Town",
        season = 1,
        episode = id.toInt(),
        sortOrder = id.toInt(),
    )
}
