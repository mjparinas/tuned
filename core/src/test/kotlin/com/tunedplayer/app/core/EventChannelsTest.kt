package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventChannelsTest {
    @Test
    fun sportsPayPerViewAndEventSlotsMatch() {
        assertTrue(EventChannels.matches("PPV 7", "US| PPV"))
        assertTrue(EventChannels.matches("PPV 12: No Event", "Events"))
        assertTrue(EventChannels.matches("NHL 05", "NHL Game Centre"))
        assertTrue(EventChannels.matches("UFC Fight Pass", "US| Entertainment"))
        assertTrue(EventChannels.matches("US| ESPN+ 14", "US| ESPN PLUS"))
        assertTrue(EventChannels.matches("DAZN 3", "CA| DAZN"))
        assertTrue(EventChannels.matches("Sky Sports Main Event", "UK"))
        assertTrue(EventChannels.matches("Channel 4", "UK| Football"))
    }

    @Test
    fun otherChannelsDoNot() {
        assertFalse(EventChannels.matches("CNN", "US| News"))
        assertFalse(EventChannels.matches("HGTV", "US| Entertainment"))
        assertFalse(EventChannels.matches("Disney Junior", "Kids"))
        assertFalse(EventChannels.matches("Prevent TV", "Lifestyle")) // "event" only as a whole word
    }
}
