package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordingWindowTest {
    private val minute = 60_000L

    @Test
    fun `starts early and ends late by the padding`() {
        val s = RecordingWindow.slot("a", start = 100 * minute, stop = 160 * minute, paddingMinutes = 2)
        assertEquals(98 * minute, s.from)
        assertEquals(162 * minute, s.until)
    }

    @Test
    fun `a programme with no end records for two hours`() {
        val s = RecordingWindow.slot("a", start = 100 * minute, stop = 0, paddingMinutes = 0)
        assertEquals(220 * minute, s.until)
    }

    @Test
    fun `never longer than six hours`() {
        val s = RecordingWindow.slot("a", start = 0, stop = 24 * 60 * minute, paddingMinutes = 5)
        assertEquals(6 * 60 * minute, s.until - s.from)
    }

    @Test
    fun `two recordings can't share the connection`() {
        val first = RecordingWindow.slot("a", 100 * minute, 160 * minute, 2)
        val overlapping = RecordingWindow.slot("b", 150 * minute, 200 * minute, 2)
        val after = RecordingWindow.slot("c", 170 * minute, 200 * minute, 2)
        assertEquals(first, RecordingWindow.clash(listOf(first), overlapping))
        assertNull(RecordingWindow.clash(listOf(first), after))
        assertNull(RecordingWindow.clash(listOf(first), first), "setting the same one again isn't a clash")
    }

    @Test
    fun `the slot survives being stored as a tag`() {
        val s = RecordingWindow.slot("12@3400", 100 * minute, 160 * minute, 2)
        assertEquals(s, RecordingWindow.fromTag(RecordingWindow.tag(s)))
        assertNull(RecordingWindow.fromTag("reminder-key:1@2"))
    }
}
