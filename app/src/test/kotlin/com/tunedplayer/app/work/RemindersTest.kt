package com.tunedplayer.app.work

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RemindersTest {
    /** Each reminder needs a tap of its own: Android merges taps with the same request code and keeps the last extras. */
    @Test
    fun `two alerts on one channel tap through to their own events`() {
        val channel = 42L
        assertNotEquals(Reminders.requestCode(channel, 1_000_000L), Reminders.requestCode(channel, 2_000_000L))
    }

    /** A reminder left in the shade kept a dot on the app icon for days after the programme ended. */
    @Test
    fun `a reminder goes away when its programme ends`() {
        val start = 10 * HOUR
        assertEquals(2 * HOUR, Reminders.expiresAfter(start = start, stop = start + 2 * HOUR, now = start))
    }

    @Test
    fun `with no end in the guide it goes three hours after the start`() {
        val start = 10 * HOUR
        assertEquals(3 * HOUR + 5 * MINUTE, Reminders.expiresAfter(start = start, stop = 0, now = start - 5 * MINUTE))
    }

    @Test
    fun `a reminder that fires late still stays a few minutes`() {
        val start = 10 * HOUR
        assertEquals(5 * MINUTE, Reminders.expiresAfter(start = start, stop = start + HOUR, now = start + 2 * HOUR))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
