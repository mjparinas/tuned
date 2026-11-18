package app.tuned.data

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Every connection is in use" must only come from other devices. A download running when Home asked the server
 * used to count as someone else watching, so tapping a channel waited for a press of play.
 */
class AccountRepositoryTest {
    private val now = 1_000_000_000L

    @Test
    fun `an answer taken while a download or the player is open is not trusted`() {
        assertFalse(AccountRepository.withoutOwnStream(ownStreamOpen = true, noStreamSince = 0, now = now))
    }

    @Test
    fun `an answer taken just after Tuned closed its stream is not trusted`() {
        assertFalse(AccountRepository.withoutOwnStream(ownStreamOpen = false, noStreamSince = now - 5_000, now = now))
    }

    @Test
    fun `an answer taken with nothing of Tuned's open for a while is`() {
        assertTrue(
            AccountRepository.withoutOwnStream(
                ownStreamOpen = false,
                noStreamSince = now - AccountRepository.OWN_STREAM_GONE_MS,
                now = now,
            ),
        )
    }
}
