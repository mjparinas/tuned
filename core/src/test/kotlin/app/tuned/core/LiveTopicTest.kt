package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTopicTest {
    @Test
    fun `sports categories are found by their words`() {
        for (group in listOf("CA| Sports", "US| NFL Sunday Ticket", "UK| Sky Sports", "Premier League", "FR| beIN", "UFC", "ESPN+")) {
            assertTrue(LiveTopic.SPORTS.matches(group), group)
        }
    }

    @Test
    fun `short words count only whole`() {
        for (group in listOf("US| Kids Nbaby", "UK| F12 Movies", "News", "US| Entertainment")) {
            assertFalse(LiveTopic.SPORTS.matches(group), group)
        }
    }

    @Test
    fun `pay-per-view and event slots are PPV`() {
        for (group in listOf("US| PPV", "Pay-Per-View", "Live Events", "EVENTOS", "PPV1")) {
            assertTrue(LiveTopic.PPV.matches(group), group)
        }
        assertFalse(LiveTopic.PPV.matches("US| Preventive Health"))
    }

    @Test
    fun `the full-text query lists every word, phrases quoted`() {
        assertEquals("ppv* OR \"pay per view\" OR event*", LiveTopic.PPV.fullText)
    }
}
