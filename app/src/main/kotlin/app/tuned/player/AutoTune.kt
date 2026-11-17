package app.tuned.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.tuned.data.TuneRepository
import app.tuned.diagnostics.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Auto-tune in the player: the queue holds the ranked channels for an event, and this tries them in order like a
 * radio's scan. A channel that errors, or shows no picture within [timeoutMs], is noted as dead and the player moves
 * on; the first one that draws a picture (at least [minHeight] tall, if set) is noted as good and stays on.
 *
 * Only one channel is ever open: moving on releases the current stream before the next one starts, and nothing is
 * fetched ahead, so a provider's connection limit is respected.
 */
internal class AutoTune(
    private val scope: CoroutineScope,
    private val tune: TuneRepository,
    private val timeoutMs: Long,
    private val minHeight: Int,
    /** Shows "trying 3 of 40" for a 1-based position, or hides the note for null. */
    private val onStatus: (Int?) -> Unit,
    /** Every channel failed. */
    private val onGiveUp: () -> Unit,
) : Player.Listener {
    lateinit var player: Player
    private var probe: Job? = null

    /** True from when a channel starts until it shows a picture. */
    var searching = false
        private set

    /** The first channel that played but below [minHeight], to fall back to if nothing sharper works. */
    private var fallback = -1
    private var settleOnNext = false

    fun begin() = probeCurrent()

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) = probeCurrent()

    override fun onRenderedFirstFrame() {
        if (!searching) return
        val id = currentId() ?: return
        probe?.cancel()
        val height = player.videoSize.height
        scope.launchSafely("AutoTune") { tune.played(id, height) }
        if (minHeight > 0 && height in 1 until minHeight) {
            if (fallback < 0) fallback = player.currentMediaItemIndex
            next()
            return
        }
        settle()
    }

    /** The current channel failed for good (while searching, or once its own retries ran out): try the next. */
    fun failCurrent() {
        val id = currentId() ?: return
        probe?.cancel()
        scope.launchSafely("AutoTune") { tune.failed(id) }
        next()
    }

    private fun probeCurrent() {
        probe?.cancel()
        if (settleOnNext) {
            settleOnNext = false
            settle()
            return
        }
        val id = currentId() ?: return
        searching = true
        onStatus(player.currentMediaItemIndex + 1)
        probe =
            scope.launchSafely("AutoTune") {
                delay(timeoutMs)
                if (searching && currentId() == id) failCurrent()
            }
    }

    private fun next() {
        when {
            player.hasNextMediaItem() -> {
                player.seekToNextMediaItem()
            }

            fallback >= 0 -> {
                settleOnNext = true
                player.seekToDefaultPosition(fallback)
            }

            else -> {
                settle()
                player.stop()
                onGiveUp()
                return
            }
        }
        player.prepare()
        player.play()
    }

    private fun settle() {
        searching = false
        onStatus(null)
    }

    private fun currentId(): Long? = player.currentMediaItem?.mediaId?.toLongOrNull()

    fun release() {
        probe?.cancel()
    }
}
