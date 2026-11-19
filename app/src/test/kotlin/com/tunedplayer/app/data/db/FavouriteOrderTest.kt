package com.tunedplayer.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FavouriteOrderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun newFavouritesGoLastAndAReorderSticks() = runTest {
        db.items().insertAll((1L..4L).map(::channel))
        db.library().addFavourite(FavouriteEntity(3, addedAt = 100))
        db.library().addFavourite(FavouriteEntity(1, addedAt = 200))
        db.library().addFavourite(FavouriteEntity(4, addedAt = 300))
        assertEquals(
            listOf(3L, 1L, 4L),
            db
                .library()
                .favouriteChannels()
                .first()
                .map { it.id },
        )

        db.library().reorderFavourites(listOf(4, 3, 1))
        assertEquals(
            listOf(4L, 3L, 1L),
            db
                .library()
                .favouriteChannels()
                .first()
                .map { it.id },
        )
    }

    @Test
    fun migrationKeepsFavouritesInTheOrderTheyWereAdded() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(null)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(2) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL(
                                    "CREATE TABLE `favourites` (`itemId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, " +
                                        "PRIMARY KEY(`itemId`))",
                                )
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        val old = helper.writableDatabase
        old.execSQL("INSERT INTO favourites VALUES (7, 500), (8, 100)")
        TunedDatabase.MIGRATION_2_3.migrate(old)
        val order =
            old.query("SELECT itemId FROM favourites ORDER BY position").use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
        assertEquals(listOf(8L, 7L), order)
        helper.close()
    }

    private fun channel(id: Long) = ItemEntity(
        id = id,
        kind = 0,
        name = "Channel $id",
        searchName = "channel $id",
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
