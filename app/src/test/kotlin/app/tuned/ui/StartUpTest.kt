package app.tuned.ui

import app.tuned.data.AppSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Start on the last channel used to open the player before "Who's watching?", so the channel was whoever watched
 * last and a kids profile could start on a grown-up's channel, past its PIN.
 */
class StartUpTest {
    @Test
    fun `one profile starts on the last channel`() {
        assertTrue(AppSettings(startOnLastChannel = true, profileCount = 1).startsOnLastChannel())
    }

    @Test
    fun `several profiles asked at start wait for the pick`() {
        assertFalse(AppSettings(startOnLastChannel = true, profileCount = 3, askProfileAtStart = true).startsOnLastChannel())
    }

    @Test
    fun `several profiles not asked at start still start on it`() {
        assertTrue(AppSettings(startOnLastChannel = true, profileCount = 3, askProfileAtStart = false).startsOnLastChannel())
    }

    @Test
    fun `the setting off never starts on it`() {
        assertFalse(AppSettings(startOnLastChannel = false, profileCount = 1).startsOnLastChannel())
    }
}
