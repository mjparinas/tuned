package app.tuned.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tuned.data.db.CurrentProfileEntity
import app.tuned.data.db.FavouriteEntity
import app.tuned.data.db.HistoryEntity
import app.tuned.data.db.ListEntity
import app.tuned.data.db.ListItemEntity
import app.tuned.data.db.ProfileEntity
import app.tuned.data.db.SeenEpisodeEntity
import app.tuned.data.db.TunedDatabase
import app.tuned.data.db.WatchLaterEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Sync used to carry only the profile in use's favourites, watch later and lists, and no profiles at all, so a TV
 * restored from Drive had one "Me" and a phone's kids profile never arrived (Melvin, 2026-11-19: "Does this also sync
 * user accounts?"). Every profile now travels with its avatar, PIN hash and everything it keeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val phone = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()
    private val tv = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() {
        phone.close()
        tv.close()
    }

    @Test
    fun everyProfileAndWhatItKeepsReachesTheOtherDevice() = runTest {
        val p = phone.sync()
        p.insertProfiles(
            listOf(
                ProfileEntity(1, "Melvin", 0, kids = false, pinHash = "hash-1", createdAt = 10, avatar = "fox"),
                ProfileEntity(2, "Kids", 3, kids = true, pinHash = null, createdAt = 20, avatar = "star"),
            ),
        )
        p.setCurrent(CurrentProfileEntity(profileId = 2))
        p.insertFavourites(listOf(FavouriteEntity(100, 1, 1, profileId = 1), FavouriteEntity(200, 2, 2, profileId = 2)))
        p.insertHistory(listOf(HistoryEntity(300, positionMs = 5_000, durationMs = 60_000, watchedAt = 3, profileId = 1)))
        p.insertWatchLater(listOf(WatchLaterEntity("m:400", 400, null, "Film", null, 4, profileId = 2)))
        p.insertSeen(listOf(SeenEpisodeEntity(500, "show", profileId = 1)))
        p.insertLists(listOf(ListEntity(77, "Sports", 0, 5, profileId = 1)))
        p.insertListItems(listOf(ListItemEntity(77, 600, 6, 0)))

        // The TV had its own single profile, in use as profile 3.
        tv.sync().insertProfiles(listOf(ProfileEntity(3, "TV", 1, kids = false, pinHash = null, createdAt = 1)))
        tv.sync().setCurrent(CurrentProfileEntity(profileId = 3))
        tv.sync().insertFavourites(listOf(FavouriteEntity(999, 1, 1, profileId = 3)))

        ProfileSync(tv).replace(ProfileSync(phone).profiles())

        val t = tv.sync()
        assertEquals(listOf("Melvin", "Kids"), t.profiles().map { it.name })
        assertEquals(listOf("hash-1", null), t.profiles().map { it.pinHash })
        assertEquals(listOf("fox", "star"), t.profiles().map { it.avatar })
        assertEquals(setOf(100L to 1L, 200L to 2L), t.favourites().map { it.itemId to it.profileId }.toSet())
        assertEquals(listOf(5_000L), t.history().map { it.positionMs })
        assertEquals(listOf(2L), t.watchLater().map { it.profileId })
        assertEquals(listOf(500L), t.seenEpisodes().map { it.itemId })
        assertEquals(listOf(77L to 1L), t.lists().map { it.id to it.profileId })
        assertEquals(listOf(600L), t.listItems().map { it.itemId })
        // Profile 3 is gone, so the first synced profile takes over on the TV.
        assertEquals(1L, t.currentProfile())
        // And the copy reads the same on both devices.
        assertEquals(ProfileSync(phone).profiles(), ProfileSync(tv).profiles())
    }

    @Test
    fun theProfileInUseStaysWhenTheCopyHasIt() = runTest {
        phone.sync().insertProfiles(listOf(ProfileEntity(1, "A", 0, false, null, 1), ProfileEntity(2, "B", 0, false, null, 2)))
        tv.sync().insertProfiles(listOf(ProfileEntity(2, "B", 0, false, null, 2)))
        tv.sync().setCurrent(CurrentProfileEntity(profileId = 2))

        ProfileSync(tv).replace(ProfileSync(phone).profiles())

        assertEquals(2L, tv.sync().currentProfile())
    }

    @Test
    fun aCopyFromBeforeProfilesSyncedChangesNothing() = runTest {
        tv.sync().insertProfiles(listOf(ProfileEntity(1, "TV", 0, false, null, 1)))
        tv.sync().insertFavourites(listOf(FavouriteEntity(9, 1, 1, profileId = 1)))

        ProfileSync(tv).replace(emptyList())

        assertEquals(listOf("TV"), tv.sync().profiles().map { it.name })
        assertEquals(listOf(9L), tv.sync().favourites().map { it.itemId })
    }
}
