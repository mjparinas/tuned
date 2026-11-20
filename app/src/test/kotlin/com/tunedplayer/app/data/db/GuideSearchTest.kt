package com.tunedplayer.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.Search
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Whole-guide search (Melvin, 2026-11-20: "improve guide search"). It used to match only programmes on now or within
 * the hour, so tomorrow's game couldn't be found at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuideSearchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    private suspend fun titles(
        text: String,
        hidden: List<String> = emptyList(),
    ) = GuideSearchIndex.search(db, text, NOW, hidden, adult = false, limit = 50).map { it.title to it.channelName }

    private suspend fun writeGuide(vararg programmes: ProgrammeEntity) {
        val writer = GuideWriter(db)
        writer.begin()
        writer.add(programmes.toList())
        writer.commit()
    }

    @Test
    fun findsTomorrowsGameNotOnlyWhatsOnWithinTheHour() = runTest {
        db.items().insertAll(listOf(channel(1, "TSN 1", "tsn"), channel(2, "News 24", "news")))
        writeGuide(
            programme("tsn", NOW - HOUR, "Hockey: Leafs at Canucks", 2 * HOUR),
            programme("tsn", NOW + DAY, "Hockey: Leafs at Habs"),
            programme("news", NOW + 2 * DAY, "The Leafs Report"),
            programme("tsn", NOW + 9 * DAY, "Leafs next season"),
            programme("tsn", NOW - 3 * HOUR, "Leafs earlier"),
        )

        assertEquals(
            listOf(
                "Hockey: Leafs at Canucks" to "TSN 1",
                "Hockey: Leafs at Habs" to "TSN 1",
                "The Leafs Report" to "News 24",
            ),
            titles("leafs"),
        )
        // Word starts, any case, accents ignored, every word needed.
        assertEquals(listOf("Hockey: Leafs at Habs" to "TSN 1"), titles("LEAF HAB"))
        assertEquals(emptyList(), titles("leafs bruins"))
    }

    @Test
    fun accentsDontMatter() = runTest {
        db.items().insertAll(listOf(channel(1, "Kids", "k")))
        writeGuide(programme("k", NOW + HOUR, "Pokémon Horizons"))
        assertEquals(listOf("Pokémon Horizons" to "Kids"), titles("pokemon"))
    }

    @Test
    fun aProgrammeIsShownOnceOnItsBestChannelAndHiddenChannelsStayOut() = runTest {
        db.items().insertAll(
            listOf(channel(1, "TSN 1 HD", "tsn"), channel(2, "TSN 1 backup", "tsn"), channel(3, "Hidden sports", "hid", group = "Hidden")),
        )
        db.library().addFavourite(FavouriteEntity(2, NOW))
        writeGuide(programme("tsn", NOW + HOUR, "Curling"), programme("hid", NOW + HOUR, "Curling final"))

        assertEquals(listOf("Curling" to "TSN 1 backup", "Curling final" to "Hidden sports"), titles("curling"))
        assertEquals(listOf("Curling" to "TSN 1 backup"), titles("curling", hidden = listOf("g:0:Hidden")))
    }

    @Test
    fun aNewGuideReplacesTheOldOnesTitles() = runTest {
        db.items().insertAll(listOf(channel(1, "TSN 1", "tsn")))
        writeGuide(programme("tsn", NOW + HOUR, "Old show"))
        writeGuide(programme("tsn", NOW + HOUR, "New show"))

        assertEquals(emptyList(), titles("old"))
        assertEquals(listOf("New show" to "TSN 1"), titles("show"))
    }

    @Test
    fun aGuideCachedBeforeTheIndexIsIndexedOnce() = runTest {
        db.items().insertAll(listOf(channel(1, "TSN 1", "tsn")))
        db.programmes().insertAll(listOf(programme("tsn", NOW + HOUR, "Grey Cup")))
        assertEquals(emptyList(), titles("grey"))

        GuideSearchIndex.fillIfMissing(db)
        GuideSearchIndex.fillIfMissing(db)
        assertEquals(listOf("Grey Cup" to "TSN 1"), titles("grey"))
    }

    @Test
    fun signingOutEmptiesTheIndex() = runTest {
        db.items().insertAll(listOf(channel(1, "TSN 1", "tsn")))
        writeGuide(programme("tsn", NOW + HOUR, "Grey Cup"))
        db.wipe()
        db.items().insertAll(listOf(channel(1, "TSN 1", "tsn")))
        db.programmes().insertAll(listOf(programme("tsn", NOW + HOUR, "Something else")))

        assertEquals(emptyList(), titles("grey"))
    }

    private fun programme(
        channel: String,
        start: Long,
        title: String,
        length: Long = HOUR,
    ) = ProgrammeEntity(channelId = channel, start = start, stop = start + length, title = title, description = null)

    private fun channel(
        id: Long,
        name: String,
        tvgId: String,
        group: String = "Sports",
    ) = ItemEntity(
        id = id,
        kind = 0,
        name = name,
        searchName = Search.normalize(name),
        groupName = group,
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

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
