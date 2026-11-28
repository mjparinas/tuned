package com.tunedplayer.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The category chips count a tab's streams from the category index alone (Melvin, 2026-11-28: "does the db need more
 * indexes?"), instead of opening every row and testing its name for 24/7, kids and empty slots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CategoryIndexTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun theCategoryChipsReadOnlyTheIndex() {
        val (sql, args) =
            SqlArgs.bind(
                "EXPLAIN QUERY PLAN $GROUP_COUNTS",
                mapOf("kind" to 0, "hidden" to listOf("247", "kids", "placeholder", "scan:dead"), "adult" to false),
            )
        val plan =
            db.openHelper.writableDatabase.query(SimpleSQLiteQuery(sql, args)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
            }
        assertTrue(plan.any { "COVERING INDEX ${TunedDatabase.CATEGORY_INDEX}" in it }, plan.joinToString("\n"))
    }

    @Test
    fun migration25To26WidensTheCategoryIndex() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("category-index-upgrade")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(25) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    "CREATE TABLE items (id INTEGER NOT NULL, kind INTEGER NOT NULL, groupName TEXT NOT NULL, " +
                                        "region TEXT, adult INTEGER NOT NULL, sortOrder INTEGER NOT NULL, " +
                                        "tags INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id))",
                                )
                                db.execSQL("CREATE INDEX index_items_kind_groupName ON items (kind, groupName)")
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        val sql = helper.writableDatabase
        TunedDatabase.MIGRATION_25_26.migrate(sql)
        assertTrue(indexExists(sql, TunedDatabase.CATEGORY_INDEX))
        assertFalse(indexExists(sql, "index_items_kind_groupName"))
        helper.close()
        context.deleteDatabase("category-index-upgrade")
    }

    private fun indexExists(
        db: SupportSQLiteDatabase,
        name: String,
    ): Boolean = db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(name)).use { it.moveToFirst() }
}
