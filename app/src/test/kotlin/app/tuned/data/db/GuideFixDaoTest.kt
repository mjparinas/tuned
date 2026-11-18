package app.tuned.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tuned.core.GuideFixes
import app.tuned.core.Search
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A channel's guide fix must survive a playlist refresh and show the source's programmes, moved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideFixDaoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.guideFixes()

    @After
    fun close() = db.close()

    @Test
    fun aFixKeepsItsIdAcrossAPlaylistRefresh() = runTest {
        db.items().insertAll(listOf(item(1, "tsn.ca"), item(2, "cnn.us")))
        dao.save(GuideFixEntity(1, guideId = "tsn1.ca", offsetMinutes = 60, playlistTvgId = "tsn.ca"))
        dao.setTvgId(1, GuideFixes.fixedId(1))

        // A refresh writes the playlist's own ids again, with channel 1's renamed and channel 3 gone.
        db.items().insertAll(listOf(item(1, "tsn-new.ca"), item(2, "cnn.us")))
        dao.save(GuideFixEntity(3, guideId = null, offsetMinutes = 30, playlistTvgId = "gone"))
        dao.reapply()

        assertEquals("fix:1", db.items().get(1)?.tvgId)
        assertEquals("tsn-new.ca", dao.get(1)?.playlistTvgId)
        assertEquals("cnn.us", db.items().get(2)?.tvgId)
        assertNull(dao.get(3))
    }

    @Test
    fun copiedProgrammesAreMoved() = runTest {
        db.programmes().insertAll(
            listOf(
                ProgrammeEntity(channelId = "tsn1.ca", start = 0, stop = 100, title = "Hockey", description = null),
                ProgrammeEntity(channelId = "fix:1", start = 5, stop = 6, title = "Stale", description = null),
            ),
        )
        dao.copyProgrammes("tsn1.ca", "fix:1", 3_600_000)

        val copied = db.programmes().nowAndNext("fix:1", 0)
        assertEquals(listOf("Hockey"), copied.map { it.title })
        assertEquals(3_600_000L, copied.single().start)
        assertEquals(1, dao.programmeCount("tsn1.ca"))
    }

    private fun item(
        id: Long,
        tvgId: String,
    ) = ItemEntity(
        id = id,
        kind = 0,
        name = "Channel $id",
        searchName = Search.normalize("Channel $id"),
        groupName = "All",
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
