package app.tuned.core

import app.tuned.core.Events.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventsTest {
    private val hour = 3_600_000L

    @Test
    fun eventsAreMatchesAndNamedEvents() {
        assertTrue(Events.isEvent("Toronto Maple Leafs vs. Vancouver Canucks"))
        assertTrue(Events.isEvent("Arsenal v Chelsea"))
        assertTrue(Events.isEvent("Lakers @ Celtics"))
        assertTrue(Events.isEvent("UFC 312: Du Plessis vs. Strickland 2"))
        assertTrue(Events.isEvent("LIVE: Formula 1 Grand Prix of Las Vegas"))
        assertTrue(Events.isEvent("Premier League Darts Final"))
    }

    @Test
    fun showsAndReplaysAreNot() {
        assertFalse(Events.isEvent("SportsCenter"))
        assertFalse(Events.isEvent("NHL Tonight"))
        assertFalse(Events.isEvent("Arsenal v Chelsea Highlights"))
        assertFalse(Events.isEvent("UFC 312 Countdown"))
        assertFalse(Events.isEvent("Paid Programming"))
        assertFalse(Events.isEvent("Golf"))
    }

    @Test
    fun sportyChannelsAreFoundByNameOrCategory() {
        assertTrue(Events.sportyChannel("US| ESPN HD", "US| Entertainment"))
        assertTrue(Events.sportyChannel("PPV 7", "US| PPV"))
        assertTrue(Events.sportyChannel("Sky Sports Main Event", "UK"))
        assertFalse(Events.sportyChannel("CNN", "US| News"))
    }

    @Test
    fun theSameEventOnSeveralChannelsIsOne() {
        val rows =
            listOf(
                Row(1, "US| ESPN", "Sports", "LIVE: Leafs vs. Canucks", 10 * hour, 13 * hour),
                Row(2, "CA| Sportsnet", "Sports", "Leafs vs. Canucks (L)", 10 * hour + 5 * 60_000, 13 * hour),
                Row(3, "US| ESPN 2", "Sports", "Leafs vs. Canucks", 22 * hour, 25 * hour), // a replay hours later
                Row(4, "PPV 1", "PPV", "UFC 312: Main Card", 9 * hour, 11 * hour),
                Row(5, "PPV 2", "PPV", "Bills vs. Jets", 6 * hour, 9 * hour), // over
                Row(6, "US| ESPN", "Sports", "SportsCenter", 13 * hour, 14 * hour),
            )
        val events = Events.group(rows, now = 10 * hour + 30 * 60_000)
        assertEquals(listOf("UFC 312: Main Card", "Leafs vs. Canucks", "Leafs vs. Canucks"), events.map { it.title })
        assertEquals(listOf(1L, 2L), events[1].channels)
        assertTrue(events[0].liveAt(10 * hour + 30 * 60_000))
    }

    @Test
    fun followedTeamsAndLeaguesMatchWholeWords() {
        val e = Events.group(listOf(Row(1, "ESPN", "Sports", "Leafs vs. Canucks", 0, hour)), now = 0).single()
        assertTrue(Events.follows(e, listOf("canucks")))
        assertTrue(Events.follows(e, listOf("Leafs")))
        assertFalse(Events.follows(e, listOf("Can")))
        assertFalse(Events.follows(e, listOf("")))
    }
}
