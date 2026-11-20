package com.tunedplayer.app.data.db

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.LiveTopic
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.ui.browse.LivePlace
import com.tunedplayer.app.ui.browse.LiveQuick
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Live TV's quick filters: a kind of channel (Sports, News, Adult and so on), a country or a language, alone or
 * together, on top of the chip chosen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveQuickFilterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private val channels =
        listOf(
            channel(1, "TSN 1", "CA| Sports", "CA"),
            channel(2, "CBC News", "CA| News", "CA"),
            channel(3, "ESPN", "US| Entertainment", "US"),
            channel(4, "PPV 7", "US| PPV", "US"),
            channel(5, "UFC335 Main Card", "Events", null),
            channel(6, "TF1", "FR| Général", "FR"),
            channel(7, "beIN Sports 1", "FR| Sport", "FR"),
            channel(8, "Live Events 03", "UK| Specials", "UK"),
            channel(9, "Nbaby TV", "US| Kids", "US"),
            channel(10, "Sky Cinema F12", "UK| Movies", "UK"),
            channel(11, "NFL RedZone", "US| NFL", "US"),
            channel(12, "Preventive Health", "US| Health", "US"),
            channel(13, "MTV Hits", "Music", null),
            channel(14, "Nat Geo Wild", "Entertainment", null),
            channel(15, "Late Night", "XXX", null, adult = true),
        )

    private suspend fun shown(
        quick: LiveQuick,
        adult: Boolean = false,
    ): List<Long> {
        db.items().insertAll(channels)
        val groups = channels.map { it.groupName }.distinct()
        val source =
            db.items().channels(
                0,
                emptyList(),
                adult,
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
    fun noFilterShowsEverything() = runTest {
        assertEquals((1L..14L).toList(), shown(LiveQuick()))
    }

    @Test
    fun sportsFindsSportsCategoriesAndNames() = runTest {
        // By category (TSN 1, NFL RedZone) or by name (ESPN, UFC335, beIN Sports); "Nbaby" isn't NBA, and "F12" isn't F1.
        assertEquals(listOf(1L, 3L, 5L, 7L, 11L), shown(LiveQuick(topic = LiveTopic.SPORTS)))
    }

    @Test
    fun ppvFindsPayPerViewAndEventSlots() = runTest {
        // By name (PPV 7, Live Events 03) or category (Events); "Preventive" has "event" inside a word, so it stays out.
        assertEquals(listOf(4L, 5L, 8L), shown(LiveQuick(topic = LiveTopic.PPV)))
    }

    @Test
    fun genresFindTheirCategoriesAndNames() = runTest {
        assertEquals(listOf(2L), shown(LiveQuick(topic = LiveTopic.NEWS)))
        assertEquals(listOf(9L), shown(LiveQuick(topic = LiveTopic.KIDS)))
        assertEquals(listOf(10L), shown(LiveQuick(topic = LiveTopic.MOVIES)))
        assertEquals(listOf(13L), shown(LiveQuick(topic = LiveTopic.MUSIC)))
        // By name alone: Nat Geo is in a general category.
        assertEquals(listOf(14L), shown(LiveQuick(topic = LiveTopic.DOCUMENTARY)))
    }

    @Test
    fun adultKeepsOnlyAdultChannelsAndOnlyWhileUnlocked() = runTest {
        assertEquals(listOf(15L), shown(LiveQuick(topic = LiveTopic.ADULT), adult = true))
        // Locked, the Adult filter finds nothing rather than getting past the PIN.
        assertEquals(emptyList(), shown(LiveQuick(topic = LiveTopic.ADULT), adult = false))
    }

    @Test
    fun aCountryKeepsOnlyItsChannels() = runTest {
        assertEquals(listOf(1L, 2L), shown(LiveQuick(place = LivePlace.Country("CA"))))
    }

    @Test
    fun aLanguageKeepsEveryCountryThatSpeaksIt() = runTest {
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 8L, 9L, 10L, 11L, 12L),
            shown(LiveQuick(place = LivePlace.Language("en", listOf("CA", "UK", "US")))),
        )
    }

    @Test
    fun aTopicAndAPlaceNarrowTogether() = runTest {
        assertEquals(listOf(7L), shown(LiveQuick(LiveTopic.SPORTS, LivePlace.Language("fr", listOf("FR")))))
    }

    @Test
    fun theFiltersOfferOnlyCountriesOnShow() = runTest {
        db.items().insertAll(channels)
        val regions = db.liveFilters().liveRegions(listOf("UK"), adult = false).first()
        assertEquals(mapOf("CA" to 2, "FR" to 2, "US" to 5), regions.associate { it.region to it.count })
    }

    private fun channel(
        id: Long,
        name: String,
        group: String,
        region: String?,
        adult: Boolean = false,
    ) = ItemEntity(
        id = id,
        kind = 0,
        name = name,
        searchName = Search.normalize(name),
        groupName = group,
        region = region,
        adult = adult,
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
