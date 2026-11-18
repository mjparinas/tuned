package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FastScrollTest {
    @Test
    fun thumbRunsFromTopToBottom() {
        assertEquals(0f, FastScroll.fraction(first = 0, visible = 10, total = 40_000))
        assertEquals(0.5f, FastScroll.fraction(first = 19_995, visible = 10, total = 40_000))
        assertEquals(1f, FastScroll.fraction(first = 39_990, visible = 10, total = 40_000))
    }

    @Test
    fun aListThatFitsKeepsTheThumbAtTheTop() {
        assertEquals(0f, FastScroll.fraction(first = 0, visible = 10, total = 8))
        assertEquals(0f, FastScroll.fraction(first = 0, visible = 0, total = 0))
    }

    @Test
    fun draggingMapsBackToTheSameRow() {
        val total = 90_000
        val visible = 12
        for (first in listOf(0, 1, 500, 44_994, 89_988)) {
            assertEquals(first, FastScroll.index(FastScroll.fraction(first, visible, total), visible, total))
        }
    }

    @Test
    fun draggingPastTheEndsStopsAtTheEnds() {
        assertEquals(0, FastScroll.index(-0.3f, visible = 10, total = 1_000))
        assertEquals(990, FastScroll.index(1.7f, visible = 10, total = 1_000))
        assertEquals(0, FastScroll.index(0.5f, visible = 10, total = 4))
    }

    @Test
    fun onlyLongListsGetOne() {
        assertFalse(FastScroll.needed(visible = 10, total = 25))
        assertTrue(FastScroll.needed(visible = 10, total = 31))
        assertFalse(FastScroll.needed(visible = 0, total = 500))
    }

    @Test
    fun bubbleShowsTheCategoryAndNumber() {
        assertEquals("102 · UK | Sports", FastScroll.label(" UK | Sports ", number = 102))
        assertEquals("102", FastScroll.label("", number = 102))
        assertEquals("Action", FastScroll.label("Action"))
        assertNull(FastScroll.label(null))
        assertNull(FastScroll.label("  "))
    }
}
