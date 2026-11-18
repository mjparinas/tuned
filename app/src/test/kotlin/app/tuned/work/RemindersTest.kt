package app.tuned.work

import org.junit.Test
import kotlin.test.assertNotEquals

/** Each reminder needs a tap of its own: Android merges taps with the same request code and keeps the last extras. */
class RemindersTest {
    @Test
    fun `two alerts on one channel tap through to their own events`() {
        val channel = 42L
        assertNotEquals(Reminders.requestCode(channel, 1_000_000L), Reminders.requestCode(channel, 2_000_000L))
    }
}
