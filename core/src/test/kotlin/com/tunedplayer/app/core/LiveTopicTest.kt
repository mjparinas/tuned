package com.tunedplayer.app.core

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

    @Test
    fun `genre categories are found by their words`() {
        val cases =
            mapOf(
                LiveTopic.NEWS to listOf("CA| News", "US| CNN", "Al Jazeera", "FR| BFM", "ES| Noticias", "DE| Nachrichten"),
                LiveTopic.KIDS to listOf("US| Kids", "Children", "UK| CBeebies", "Cartoons", "Nick Jr", "FR| Jeunesse", "ES| Infantil"),
                LiveTopic.MOVIES to listOf("24/7 Movies", "UK| Sky Cinema", "HBO", "DE| Filme", "ES| Peliculas", "FR| Cinéma"),
                LiveTopic.MUSIC to listOf("Music", "MTV", "FR| Musique", "DE| Musik", "Stingray Karaoke"),
                LiveTopic.DOCUMENTARY to listOf("Documentaries", "FR| Documentaire", "Nat Geo", "Discovery", "DE| Doku", "History"),
            )
        for ((topic, groups) in cases) {
            for (group in groups) assertTrue(topic.matches(group), "$topic: $group")
        }
    }

    @Test
    fun `genres leave other categories alone`() {
        for (topic in listOf(LiveTopic.NEWS, LiveTopic.KIDS, LiveTopic.MOVIES, LiveTopic.MUSIC, LiveTopic.DOCUMENTARY)) {
            for (group in listOf("US| Entertainment", "CA| Sports", "UK| General", "PPV")) {
                assertFalse(topic.matches(group), "$topic: $group")
            }
        }
        // Whole words only where a prefix would catch others: "DW" isn't "Dwarf", "Nick" isn't "Nickname".
        assertFalse(LiveTopic.NEWS.matches("Dwarf TV"))
        assertFalse(LiveTopic.KIDS.matches("Nickname"))
    }

    @Test
    fun `adult has no words, since it is the adult flag`() {
        assertTrue(LiveTopic.ADULT.isAdult)
        assertEquals("", LiveTopic.ADULT.fullText)
        assertFalse(LiveTopic.ADULT.matches("XXX"))
    }
}
