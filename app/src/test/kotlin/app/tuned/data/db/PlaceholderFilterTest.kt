package app.tuned.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tuned.core.Placeholders
import app.tuned.core.Search
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Empty event slots ("- NO EVENT STREAMING - | ... PPV 43") stay out of search while the setting is on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaceholderFilterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun emptyEventSlotsAreHiddenAndComeBackOnceNamed() = runTest {
        db.items().insertAll(
            listOf(
                channel(1, "- NO EVENT STREAMING - | 8K EXCLUSIVE | SE: PLAY+ PPV 43"),
                channel(2, "SE: PLAY+ PPV 44 | UFC 310"),
            ),
        )

        fun search(hidden: List<String>) = db.items().searchChannels("ppv", 0, hidden, adult = false, limit = 10).map { it.id }

        assertEquals(listOf(2L), search(listOf(Placeholders.HIDDEN_KEY)))
        assertEquals(listOf(1L, 2L), search(emptyList()))

        // The provider names the slot for an event: it shows again.
        db.items().insertAll(listOf(channel(1, "SE: PLAY+ PPV 43 | Boxing")))
        assertEquals(listOf(1L, 2L), search(listOf(Placeholders.HIDDEN_KEY)))
    }

    private fun channel(
        id: Long,
        name: String,
    ) = ItemEntity(
        id = id,
        kind = 0,
        name = name,
        searchName = Search.normalize(name),
        groupName = "SWEDEN PLAY+ PPV",
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
