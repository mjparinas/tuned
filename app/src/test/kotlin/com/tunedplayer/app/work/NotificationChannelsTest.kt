package com.tunedplayer.app.work

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Only what someone would come back for puts a dot on the app icon (Melvin, 2026-11-19). */
class NotificationChannelsTest {
    @Test
    fun `only reminders and new versions badge the app icon`() {
        val badged =
            NotificationChannels.ALL
                .filter { it.badge }
                .map { it.id }
                .toSet()
        assertEquals(setOf(NotificationChannels.REMINDERS, NotificationChannels.UPDATES), badged)
    }

    @Test
    fun `progress channels are new ids, since Android keeps the badge choice of a channel it already made`() {
        val ids = NotificationChannels.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(NotificationChannels.RETIRED.none { it in ids })
    }
}
