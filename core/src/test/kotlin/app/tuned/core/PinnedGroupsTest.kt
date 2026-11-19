package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinnedGroupsTest {
    private val live = 0
    private val films = 1

    @Test
    fun pinnedGroupsComeFirstInTheOrderTheyWerePinned() {
        var stored = ""
        stored = PinnedGroups.toggle(stored, live, "UK Sports")
        stored = PinnedGroups.toggle(stored, live, "CA News")
        val groups = listOf("AU General", "CA News", "UK Sports", "US Movies")
        assertEquals(listOf("UK Sports", "CA News", "AU General", "US Movies"), PinnedGroups.order(groups, stored, live) { it })
    }

    @Test
    fun pinningAgainUnpins() {
        val stored = PinnedGroups.toggle(PinnedGroups.toggle("", live, "UK Sports"), live, "UK Sports")
        assertFalse(PinnedGroups.isPinned(stored, live, "UK Sports"))
        assertEquals(listOf("A", "B"), PinnedGroups.order(listOf("A", "B"), stored, live) { it })
    }

    @Test
    fun eachTabKeepsItsOwnPins() {
        val stored = PinnedGroups.toggle("", films, "Action")
        assertTrue(PinnedGroups.isPinned(stored, films, "Action"))
        assertFalse(PinnedGroups.isPinned(stored, live, "Action"))
        assertEquals(listOf("Drama", "Action"), PinnedGroups.order(listOf("Drama", "Action"), stored, live) { it })
    }

    @Test
    fun aSixthPinLetsGoOfTheOldest() {
        var stored = PinnedGroups.toggle("", films, "Action")
        for (g in listOf("A", "B", "C", "D", "E")) stored = PinnedGroups.toggle(stored, live, g)
        stored = PinnedGroups.toggle(stored, live, "F")
        assertFalse(PinnedGroups.isPinned(stored, live, "A"))
        assertTrue(PinnedGroups.isPinned(stored, live, "F"))
        // Another tab's pins don't count toward this one's five.
        assertTrue(PinnedGroups.isPinned(stored, films, "Action"))
        assertEquals(listOf("B", "C", "D", "E", "F", "A"), PinnedGroups.order(listOf("A", "B", "C", "D", "E", "F"), stored, live) { it })
    }
}
