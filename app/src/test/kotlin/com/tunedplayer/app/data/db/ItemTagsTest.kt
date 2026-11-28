package com.tunedplayer.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Search
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hiding filters read tags worked out when a stream is saved (Melvin, 2026-11-28: "speed up counts on the db"),
 * in Kotlin through Room and in SQL for streams changed in place: the two must agree on every name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ItemTagsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private val names =
        listOf(
            "News" to "US| NEWS",
            "Movies 24/7" to "All",
            "Movies 24-7" to "All",
            "Movies 24x7" to "All",
            "Movies 24 7" to "All",
            "Movies 24 / 7" to "All",
            "Friends 247" to "All",
            "Channel 1247" to "All",
            "Channel 24/70" to "All",
            "24/7 Comedy" to "All",
            "Simpsons" to "24/7 SHOWS",
            "Disney Junior" to "US| ENT",
            "Nick Jr" to "All",
            "Cartoon Network" to "All",
            "Anything" to "KIDS ZONE",
            "Anything" to "Family",
            "Anything" to "ANIMATION",
            "Kidnapped" to "Movies",
            "ESPN+ 03: No Event" to "PPV",
            "UFC: no live event today" to "PPV",
            "Main Event" to "PPV",
            "ÉVÉNEMENT 24/7" to "FR| ÉVÉNEMENTS",
            "KİDS" to "TÜRK",
            "" to "",
        )

    @Test
    fun kotlinAndSqlTagEveryStreamAlike() = runTest {
        val items =
            names.flatMapIndexed { n, (name, group) ->
                (0..2).map { kind -> item(n * 3L + kind + 1, kind, name, group) }
            }
        db.items().insertAll(items)

        val sql =
            db.openHelper.readableDatabase.query("SELECT id, tags, ${ItemTags.SQL} FROM items ORDER BY id").use { c ->
                buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getInt(1), c.getInt(2))) }
            }

        assertEquals(items.size, sql.size)
        for ((id, kotlin, fromSql) in sql) assertEquals(fromSql, kotlin, "stream $id: ${items.first { it.id == id }}")
        val byName = items.filter { it.kind == 0 }.associate { "${it.groupName}/${it.name}" to it.tags }
        assertEquals(ItemTags.ROUND_THE_CLOCK, byName.getValue("All/Movies 24/7"))
        assertEquals(0, byName.getValue("All/Channel 1247"))
        assertEquals(ItemTags.KIDS, byName.getValue("US| ENT/Disney Junior"))
        assertEquals(ItemTags.PLACEHOLDER, byName.getValue("PPV/ESPN+ 03: No Event"))
    }

    @Test
    fun aRenameIsTaggedAgain() = runTest {
        db.items().insertAll(listOf(item(1, 0, "News", "All")))

        db.channelEdits().show(1, "Comedy 24/7", null, Search.normalize("Comedy 24/7"))

        assertEquals(ItemTags.ROUND_THE_CLOCK, db.items().get(1)?.tags)
    }

    @Test
    fun theUpgradeTagsTheCachedPlaylist() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("item-tags-upgrade")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(24) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(V24_ITEMS)
                                db.execSQL("CREATE VIRTUAL TABLE `items_fts` USING FTS4(`searchName` TEXT NOT NULL, content=`items`)")
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
        names.forEachIndexed { n, (name, group) ->
            sql.execSQL(
                "INSERT INTO items (id, kind, name, searchName, groupName, adult, url, sortOrder) VALUES (?, 0, ?, ?, ?, 0, '', ?)",
                arrayOf<Any?>(n + 1L, name, Search.normalize(name), group, n),
            )
        }
        sql.execSQL("INSERT INTO items_fts(items_fts) VALUES('rebuild')")

        TunedDatabase.MIGRATION_24_25.migrate(sql)

        val tags =
            sql.query("SELECT id, tags FROM items ORDER BY id").use { c ->
                buildList { while (c.moveToNext()) add(c.getInt(1)) }
            }
        assertEquals(names.map { (name, group) -> ItemTags.of(0, name, group, Search.normalize(name)) }, tags)
        // The search index still finds the names, and keeps up with changes after the upgrade.
        sql.execSQL("UPDATE items SET searchName = 'weather' WHERE id = 1")
        val found =
            sql
                .query(
                    "SELECT COUNT(*) FROM items_fts WHERE items_fts MATCH 'weather'",
                ).use { it.moveToFirst() && it.getInt(0) == 1 }
        assertTrue(found)
        helper.close()
        context.deleteDatabase("item-tags-upgrade")
    }

    private fun item(
        id: Long,
        kind: Int,
        name: String,
        group: String,
    ) = ItemEntity(
        id = id,
        kind = kind,
        name = name,
        searchName = Search.normalize(name),
        groupName = group,
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

    private companion object {
        /** `items` as Room 24 made it. */
        const val V24_ITEMS =
            "CREATE TABLE `items` (`id` INTEGER NOT NULL, `kind` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "`searchName` TEXT NOT NULL, `groupName` TEXT NOT NULL, `region` TEXT, `adult` INTEGER NOT NULL, `logo` TEXT, " +
                "`url` TEXT NOT NULL, `tvgId` TEXT, `showKey` TEXT, `showName` TEXT, `season` INTEGER, `episode` INTEGER, " +
                "`sortOrder` INTEGER NOT NULL, `number` INTEGER NOT NULL DEFAULT 0, `added` INTEGER NOT NULL DEFAULT 0, " +
                "`year` INTEGER, `rating` REAL, `sortTitle` TEXT NOT NULL DEFAULT '', `topRating` REAL, PRIMARY KEY(`id`))"
    }
}
