package com.tunedplayer.app.ui.browse

import com.tunedplayer.app.core.ChannelQuality
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.LiveTopic
import com.tunedplayer.app.core.ProgrammeGenre
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The browse tabs' filters are kept as text until changed (Melvin, 2026-11-21); whatever can't be read is nothing chosen. */
class SavedFiltersTest {
    @Test
    fun everyChoiceComesBackAsItWas() {
        val saved =
            SavedFilters(
                chips =
                    mapOf(
                        ContentKind.LIVE to Filter.Recent,
                        ContentKind.MOVIE to Filter.Group("Action: 4K"),
                        ContentKind.SERIES to Filter.Favourites,
                    ),
                live =
                    LiveQuick(
                        topic = LiveTopic.SPORTS,
                        place = LivePlace.Language("fr", listOf("CA", "FR")),
                        quality = ChannelQuality.FHD,
                        onNow = ProgrammeGenre.SPORTS,
                        withGuide = true,
                        catchUp = true,
                        watched = true,
                    ),
            )
        assertEquals(saved, SavedFilters.decode(SavedFilters.encode(saved)))

        val list = SavedFilters(chips = mapOf(ContentKind.MOVIE to Filter.OwnList(7)), live = LiveQuick(place = LivePlace.Country("CA")))
        assertEquals(list, SavedFilters.decode(SavedFilters.encode(list)))
    }

    @Test
    fun theAdultFilterIsNeverKept() {
        val saved = SavedFilters(live = LiveQuick(topic = LiveTopic.ADULT, withGuide = true))
        val back = SavedFilters.decode(SavedFilters.encode(saved))
        assertNull(back.live.topic)
        assertEquals(true, back.live.withGuide)
    }

    @Test
    fun nothingSavedOrUnreadableIsNothingChosen() {
        assertEquals(SavedFilters(), SavedFilters.decode(null))
        assertEquals(SavedFilters(), SavedFilters.decode(""))
        assertEquals(SavedFilters(), SavedFilters.decode("{not json"))
        assertEquals(Filter.All, SavedFilters.decode("""{"chips":{"9":"g:Gone","1":"nonsense"}}""").chip(ContentKind.MOVIE))
        // A filter since removed from the app, or Recent outside Live TV, is dropped rather than failing the rest.
        val partly = SavedFilters.decode("""{"chips":{"1":"recent","0":"fav"},"topic":"NO_SUCH","quality":"UHD"}""")
        assertEquals(Filter.All, partly.chip(ContentKind.MOVIE))
        assertEquals(Filter.Favourites, partly.chip(ContentKind.LIVE))
        assertEquals(LiveQuick(quality = ChannelQuality.UHD), partly.live)
    }

    @Test
    fun chipsRoundTrip() {
        listOf(Filter.All, Filter.Favourites, Filter.Recent, Filter.Group("g:odd"), Filter.OwnList(3)).forEach {
            assertEquals(it, SavedFilters.decodeChip(SavedFilters.encodeChip(it)))
        }
        assertNull(SavedFilters.decodeChip("l:x"))
    }
}
