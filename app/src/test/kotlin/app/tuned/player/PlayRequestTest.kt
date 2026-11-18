package app.tuned.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The player is single-task, so a channel picked while it is open (widget, tile, game alert, the app behind
 * picture-in-picture) reaches `onNewIntent`. It was ignored and the old channel kept playing; now a different pick
 * restarts the player, and the same pick leaves it alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayRequestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `another channel is a new request`() {
        assertNotEquals(PlayRequest.of(PlayerActivity.intent(context, 1)), PlayRequest.of(PlayerActivity.intent(context, 2)))
    }

    @Test
    fun `the same channel again is not`() {
        assertEquals(PlayRequest.of(PlayerActivity.intent(context, 7)), PlayRequest.of(PlayerActivity.intent(context, 7)))
    }

    @Test
    fun `tuning to an event is not the channel already playing`() {
        assertNotEquals(PlayRequest.of(PlayerActivity.intent(context, 7)), PlayRequest.of(PlayerActivity.tuneIntent(context, "UFC 300")))
    }

    @Test
    fun `a player opened from nothing reads as no request`() {
        assertEquals(PlayRequest(0, null, 0, 0), PlayRequest.of(null))
    }
}
