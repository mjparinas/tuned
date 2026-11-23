package com.tunedplayer.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room 24's speculative-warm indexes (docs/decisions/speed.md › Speculative warm). */
@RunWith(RobolectricTestRunner::class)
class BrowseWarmIndexTest {
    @Test
    fun migration23To24AddsBrowseWarmIndexes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("warm-index-test")
                    .callback(
                        object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(23) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    "CREATE TABLE favourites (itemId INTEGER NOT NULL, addedAt INTEGER NOT NULL, " +
                                        "position INTEGER NOT NULL DEFAULT 0, profileId INTEGER NOT NULL DEFAULT 1, " +
                                        "PRIMARY KEY(profileId, itemId))",
                                )
                                db.execSQL(
                                    "CREATE TABLE list_items (listId INTEGER NOT NULL, itemId INTEGER NOT NULL, " +
                                        "addedAt INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(listId, itemId))",
                                )
                                db.execSQL(
                                    "CREATE TABLE history (itemId INTEGER NOT NULL, positionMs INTEGER NOT NULL, " +
                                        "durationMs INTEGER NOT NULL, watchedAt INTEGER NOT NULL, " +
                                        "profileId INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(profileId, itemId))",
                                )
                                db.execSQL("CREATE INDEX index_history_watchedAt ON history (watchedAt)")
                                db.execSQL(
                                    "CREATE TABLE channel_scan (itemId INTEGER NOT NULL, failures INTEGER NOT NULL, " +
                                        "firstFailAt INTEGER NOT NULL, lastFailAt INTEGER NOT NULL, lastOkAt INTEGER NOT NULL, " +
                                        "reason TEXT, kept INTEGER NOT NULL, hidden INTEGER NOT NULL, PRIMARY KEY(itemId))",
                                )
                                db.execSQL(
                                    "CREATE TABLE show_groups (rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                        "showKey TEXT NOT NULL, groupName TEXT NOT NULL, region TEXT, adult INTEGER NOT NULL, " +
                                        "showName TEXT NOT NULL, logo TEXT, episodes INTEGER NOT NULL, sortOrder INTEGER NOT NULL, " +
                                        "added INTEGER NOT NULL DEFAULT 0, year INTEGER, rating REAL, sortTitle TEXT NOT NULL DEFAULT '')",
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        val db = helper.writableDatabase
        TunedDatabase.MIGRATION_23_24.migrate(db)
        for (
        name in
        listOf(
            "index_favourites_profileId_position",
            "index_list_items_listId_position",
            "index_history_profileId_watchedAt",
            "index_channel_scan_hidden",
            "index_show_groups_added_sortOrder",
            "index_show_groups_year_sortOrder",
            "index_show_groups_rating_sortOrder",
            "index_show_groups_sortTitle_sortOrder",
        )
        ) {
            assertTrue(name, indexExists(db, name))
        }
        helper.close()
    }

    private fun indexExists(
        db: SupportSQLiteDatabase,
        name: String,
    ): Boolean = db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(name)).use { it.moveToFirst() }
}
