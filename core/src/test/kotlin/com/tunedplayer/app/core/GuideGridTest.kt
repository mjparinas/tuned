package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuideGridTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val noon = 1_800_000_000_000L - 1_800_000_000_000L % (24 * hour) + 12 * hour

    @Test
    fun ticksFallOnTheHalfHour() {
        assertEquals(noon, GuideGrid.slotStart(noon + 29 * minute))
        assertEquals(noon + 30 * minute, GuideGrid.slotStart(noon + 30 * minute))
        assertEquals(
            listOf(noon, noon + 30 * minute, noon + hour, noon + 90 * minute),
            GuideGrid.ticks(noon + 10 * minute, noon + 90 * minute),
        )
        assertEquals(emptyList(), GuideGrid.ticks(noon, noon - 1))
    }

    @Test
    fun blocksCoverTheWholeSpan() {
        val blocks = GuideGrid.blocks(noon - minute, noon + 7 * hour)
        assertTrue(blocks.first * GuideGrid.BLOCK <= noon - minute)
        assertTrue((blocks.last + 1) * GuideGrid.BLOCK >= noon + 7 * hour)
        // A span inside one block reads one block, and one ending exactly on a boundary doesn't read the next.
        assertEquals(1, GuideGrid.blocks(noon, noon + hour).count())
        val boundary = (noon / GuideGrid.BLOCK + 1) * GuideGrid.BLOCK
        assertEquals(1, GuideGrid.blocks(boundary - hour, boundary).count())
    }

    @Test
    fun scrollsBackAnHourOrAsFarAsCatchUpGoes() {
        assertEquals(noon - hour, GuideGrid.earliest(noon + 5 * minute, 0))
        assertEquals(noon - 2 * 24 * hour, GuideGrid.earliest(noon + 5 * minute, 2))
        assertEquals(noon - 7 * 24 * hour, GuideGrid.earliest(noon, 30))
        assertEquals(noon + 3 * 24 * hour, GuideGrid.latest(noon + 5 * minute, 3))
        assertEquals(noon + 24 * hour, GuideGrid.latest(noon, 0))
    }

    @Test
    fun clampsTheViewInsideTheGuide() {
        val earliest = noon - hour
        val latest = noon + 24 * hour
        assertEquals(earliest, GuideGrid.clampStart(noon - 5 * hour, earliest, latest, 2 * hour))
        assertEquals(latest - 2 * hour, GuideGrid.clampStart(latest, earliest, latest, 2 * hour))
        assertEquals(noon, GuideGrid.clampStart(noon, earliest, latest, 2 * hour))
        // A view wider than the whole guide stays at the start.
        assertEquals(earliest, GuideGrid.clampStart(noon, earliest, latest, 48 * hour))
    }

    @Test
    fun keepsOnlyProgrammesOnInTheSpan() {
        val shows = listOf(noon + hour to noon + 2 * hour, noon - hour to noon, noon - 30 * minute to noon + 30 * minute)
        val on = GuideGrid.overlapping(shows, noon, noon + hour, { it.first }, { it.second })
        // The one that ended at noon is out; the one running across noon is in, first.
        assertEquals(listOf(noon - 30 * minute to noon + 30 * minute), on)
    }

    @Test
    fun revealsAProgrammeOffEitherEdge() {
        val view = noon
        val visible = 2 * hour
        // Already in view: leave the timeline alone.
        assertEquals(noon, GuideGrid.reveal(noon + 30 * minute, noon + 90 * minute, view, visible))
        // Still on, but started before the view: leave alone, so scrolling channels does not drag time left.
        assertEquals(noon, GuideGrid.reveal(noon - 50 * minute, noon + 10 * minute, view, visible))
        // Wholly past the left edge: scroll to its start.
        assertEquals(noon - hour, GuideGrid.reveal(noon - 50 * minute, noon - 10 * minute, view, visible))
        // Off the right edge: scroll to show its start.
        assertEquals(noon + hour, GuideGrid.reveal(noon + 2 * hour, noon + 3 * hour, view, visible))
    }
}
