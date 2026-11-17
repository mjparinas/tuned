package app.tuned.player

import androidx.media3.common.Player
import app.tuned.data.TuneRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Backup copies in the player (from the 2026-11-17 app audit). When a live channel dies for good, the player swaps
 * the same spot in its list for another copy of the channel ([TuneRepository.copies]) and tries them in turn, like
 * auto-tune: a copy that errors, or shows no picture within [timeoutMs], is noted as dead and the next is tried.
 * The first one that draws a picture stays on, and next and previous keep flipping through the category as before.
 *
 * The old stream is released before a copy starts, and nothing is fetched ahead, so only one connection is open.
 */
internal class Failover(
    private val scope: CoroutineScope,
    private val tune: TuneRepository,
    private val timeoutMs: Long,
    /** Puts the copy with this id in the current spot and starts it. */
    private val swapTo: (Long) -> Unit,
    /** "Trying a backup copy (2 of 3)" for a 1-based position and total, or hides the note for null. */
    private val onStatus: (Pair<Int, Int>?) -> Unit,
    /** A copy plays. */
    private val onFound: (Long) -> Unit,
    /** Nothing worked. */
    private val onGiveUp: () -> Unit,
    /** A copy failed to play (to hide it if the internet and the service were fine). */
    private val onDead: (Long) -> Unit,
) : Player.Listener {
    lateinit var player: Player
    private var copies: List<Long> = emptyList()
    private var position = -1
    private var probe: Job? = null

    /** True from when a copy starts until it shows a picture. */
    var searching = false
        private set

    /**
     * Channel [failedId] died for good: look for its copies and try the first. Returns false when it has none, so the
     * player shows its usual error. [tried] are ids already tried in this chain, which are skipped.
     */
    suspend fun begin(
        failedId: Long,
        tried: Set<Long> = emptySet(),
    ): Boolean {
        tune.failed(failedId)
        val found = tune.copies(failedId).filterNot { it in tried }
        if (found.isEmpty()) return false
        copies = found
        position = -1
        tryNext()
        return true
    }

    override fun onRenderedFirstFrame() {
        if (!searching) return
        val id = copies.getOrNull(position) ?: return
        probe?.cancel()
        searching = false
        onStatus(null)
        val height = player.videoSize.height
        scope.launch { tune.played(id, height) }
        onFound(id)
    }

    /** The copy being tried failed: note it and move on. */
    fun failCurrent() {
        val id = copies.getOrNull(position) ?: return
        probe?.cancel()
        scope.launch { tune.failed(id) }
        onDead(id)
        tryNext()
    }

    private fun tryNext() {
        position++
        val id = copies.getOrNull(position)
        if (id == null) {
            searching = false
            onStatus(null)
            player.stop()
            onGiveUp()
            return
        }
        searching = true
        onStatus(position + 1 to copies.size)
        swapTo(id)
        probe =
            scope.launch {
                delay(timeoutMs)
                if (searching && copies.getOrNull(position) == id) failCurrent()
            }
    }

    /** The player moved to channel [id]: unless that's the copy being tried, the person flipped away, so stop looking. */
    fun onChannelChanged(id: Long) {
        if (searching && copies.getOrNull(position) == id) return
        cancel()
    }

    private fun cancel() {
        probe?.cancel()
        if (searching) onStatus(null)
        searching = false
        copies = emptyList()
        position = -1
    }

    fun release() {
        probe?.cancel()
    }
}
