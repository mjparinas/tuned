package app.tuned.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** The person's own lists beside Favourites (Melvin, 2026-11-19: "similar to how spotify does it"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnListsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private suspend fun <T : Any> PagingSource<Int, T>.all(): List<T> =
        (load(PagingSource.LoadParams.Refresh(null, 500, false)) as PagingSource.LoadResult.Page).data

    private suspend fun listChannels(listId: Long) = db
        .items()
        .listChannels(listId, 0, emptyList(), false, null, "", "", "", listOf("any"))
        .all()
        .map { it.id }

    @Test
    fun aListShowsItsChannelsInTheOrderAddedAndItsFilms() = runTest {
        db.items().insertAll(listOf(item(1, 0), item(2, 0), item(3, 0), item(4, 1)))
        db.lists().create(ListEntity(10, "Sport", position = 0, createdAt = 1))
        db.lists().addItem(ListItemEntity(10, 3, addedAt = 100))
        db.lists().addItem(ListItemEntity(10, 1, addedAt = 200))
        db.lists().addItem(ListItemEntity(10, 4, addedAt = 300))

        assertEquals(listOf(3L, 1L), listChannels(10))
        assertEquals(
            listOf(4L),
            db
                .items()
                .items(1, emptyList(), false, null, false, "", "", 10)
                .all()
                .map { it.id },
        )
        // Favourites are untouched: the list is beside them, not in them.
        assertEquals(emptyList(), db.library().allFavourites())
    }

    @Test
    fun aTabShowsChipsOnlyForListsWithSomethingInIt() = runTest {
        db.items().insertAll(listOf(item(1, 0), item(2, 1)))
        db.lists().create(ListEntity(10, "Channels", 0, 1))
        db.lists().create(ListEntity(11, "Films", 1, 2))
        db.lists().addItem(ListItemEntity(10, 1, 1))
        db.lists().addItem(ListItemEntity(11, 2, 1))

        assertEquals(
            listOf(10L),
            db
                .lists()
                .withKind(0)
                .first()
                .map { it.id },
        )
        assertEquals(
            listOf(11L),
            db
                .lists()
                .withKind(1)
                .first()
                .map { it.id },
        )
        assertEquals(listOf(10L), db.lists().listsWith(1).first())
    }

    @Test
    fun deletingAListLeavesTheChannelsAndOtherLists() = runTest {
        db.items().insertAll(listOf(item(1, 0)))
        db.lists().create(ListEntity(10, "A", 0, 1))
        db.lists().create(ListEntity(11, "B", 1, 2))
        db.lists().addItem(ListItemEntity(10, 1, 1))
        db.lists().addItem(ListItemEntity(11, 1, 1))

        db.lists().delete(10)

        assertEquals(listOf(11L), db.lists().allNow().map { it.id })
        assertEquals(emptyList(), listChannels(10))
        assertEquals(listOf(1L), listChannels(11))
        assertEquals(1, db.items().count())
    }

    @Test
    fun eachProfileKeepsItsOwnListsAndDeletingAProfileTakesThem() = runTest {
        db.lists().create(ListEntity(10, "Mine", 0, 1))
        db.profiles().setCurrent(CurrentProfileEntity(profileId = 2))
        assertEquals(emptyList(), db.lists().allNow())
        db.lists().create(ListEntity(11, "Kids", 0, 1))
        db.lists().addItem(ListItemEntity(11, 1, 1))

        db.profiles().delete(2)
        db.profiles().setCurrent(CurrentProfileEntity(profileId = 1))

        assertEquals(listOf(10L), db.lists().allNow().map { it.id })
        assertEquals(emptyList(), db.lists().allItems())
    }

    @Test
    fun syncReplacesThisProfilesListsWithTheOtherDevicesOnes() = runTest {
        db.lists().create(ListEntity(10, "Old", 0, 1))
        db.lists().addItem(ListItemEntity(10, 1, 1))

        db.lists().replaceMine(listOf(ListEntity(20, "New", 0, 5)), listOf(ListItemEntity(20, 2, 3), ListItemEntity(99, 3, 3)))

        assertEquals(listOf("New"), db.lists().allNow().map { it.name })
        // An entry for a list that isn't in the copy is left out.
        assertEquals(listOf(20L to 2L), db.lists().allItems().map { it.listId to it.itemId })
    }

    @Test
    fun theUpgradeMakesTheSameTablesAsAFreshInstall() {
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(null)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(17) {
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
        TunedDatabase.MIGRATION_17_18.migrate(old)
        val fresh = db.openHelper.writableDatabase
        for (table in listOf("lists", "list_items")) assertEquals(shape(fresh, table), shape(old, table), table)
        helper.close()
    }

    private fun rows(
        db: SupportSQLiteDatabase,
        sql: String,
    ): List<String> = db.query(sql).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
        }
    }

    private fun shape(
        db: SupportSQLiteDatabase,
        table: String,
    ): List<String> {
        val columns = rows(db, "SELECT name, type, `notnull`, dflt_value, pk FROM pragma_table_info('$table')").sorted()
        val indices = rows(db, "SELECT name FROM pragma_index_list('$table') WHERE origin = 'c'").sorted()
        return columns + indices
    }

    private fun item(
        id: Long,
        kind: Int,
    ) = ItemEntity(
        id = id,
        kind = kind,
        name = "Item $id",
        searchName = "item $id",
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
