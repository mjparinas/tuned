package app.tuned.player

import android.content.Context
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import app.tuned.AppContainer
import app.tuned.R
import app.tuned.core.ChannelScan
import app.tuned.data.AppSettings
import app.tuned.data.db.ItemEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the player does when a live channel won't play (Melvin, 2026-11-17):
 * - **Backup copies** ([Failover]): another copy of the same channel takes its spot in the list.
 * - **Hiding dead channels** (Settings › Channel health › Hide channels that won't play: "if a channel doesn't load but
 *   you know the service is up and internet is working, hide that channel from results"). A failed channel is hidden
 *   only when the phone's network is validated, the service answers, and another channel drew a picture within ten
 *   minutes, before or after ([ChannelScan.blameOnPlay]). A failure with no such proof waits for the next picture.
 */
internal class Backups(
    private val context: Context,
    private val scope: CoroutineScope,
    private val container: AppContainer,
    private val hooks: Hooks,
) {
    class Hooks(
        val settings: () -> AppSettings,
        val queue: () -> List<ItemEntity>,
        val setQueue: (List<ItemEntity>) -> Unit,
        val mediaItem: (ItemEntity) -> MediaItem,
        /** The channel now showing, for the title. */
        val showing: (ItemEntity) -> Unit,
        /** A lasting note in the middle of the screen, or null to hide it. */
        val status: (String?) -> Unit,
        /** A short note that fades. */
        val flash: (String) -> Unit,
        val casting: () -> Boolean,
    )

    private var failover: Failover? = null

    /** The last channel that drew a picture, and when. */
    private var lastPicture: Pair<Long, Long>? = null

    /** Channels that failed before any other channel played, waiting for one to play. */
    private val awaitingProof = mutableMapOf<Long, Long>()

    /** A copy is being tried. */
    val searching: Boolean get() = failover?.searching == true

    /** True when backup copies are on for this player (live channels, not auto-tune or a recording). */
    val active: Boolean get() = failover != null

    fun attach(player: ExoPlayer) {
        val f =
            Failover(
                scope = scope,
                tune = container.tune,
                timeoutMs = hooks.settings().tuneTimeoutMs.toLong(),
                swapTo = { id -> swapTo(player, id) },
                onStatus = { at -> hooks.status(at?.let { context.getString(R.string.player_trying_copy, it.first, it.second) }) },
                onFound = { id ->
                    hooks.queue().firstOrNull { it.id == id }?.let { hooks.flash(context.getString(R.string.player_copy_playing, it.name)) }
                },
                onGiveUp = { Toast.makeText(context, context.getString(R.string.player_no_copy_works), Toast.LENGTH_LONG).show() },
                onDead = ::channelFailed,
            )
        f.player = player
        player.addListener(f)
        failover = f
    }

    /** The copy being tried failed too. */
    fun failCurrent() {
        failover?.failCurrent()
    }

    fun onChannelChanged(id: Long) {
        failover?.onChannelChanged(id)
    }

    /**
     * How many times to retry a failing stream: a live channel that never showed a picture ([fresh]) gets one retry
     * before its copies are tried, so a dead channel is left quickly; otherwise the [usual] number.
     */
    fun retries(
        fresh: Boolean,
        usual: Int,
    ): Int = if (fresh && active && hooks.settings().backupCopies) 1 else usual

    /**
     * Live channel [id] died for good: note it and try its copies. False when copies don't apply here (so the player
     * shows its error now); [onNone] runs if it turns out there are none.
     */
    fun takeOver(
        id: Long?,
        onNone: () -> Unit,
    ): Boolean {
        val f = failover ?: return false
        if (id == null || hooks.casting()) return false
        channelFailed(id)
        scope.launch { if (!f.begin(id)) onNone() }
        return true
    }

    /** Puts channel [id] in the current spot of the list, so next and previous still flip the same category. */
    private fun swapTo(
        player: ExoPlayer,
        id: Long,
    ) {
        scope.launch {
            val copy = container.db.items().get(id) ?: return@launch
            val index = player.currentMediaItemIndex
            val queue = hooks.queue()
            if (index !in queue.indices) return@launch
            hooks.setQueue(queue.toMutableList().also { it[index] = copy })
            player.stop() // the old stream closes before the copy opens
            player.replaceMediaItem(index, hooks.mediaItem(copy))
            player.seekToDefaultPosition(index)
            player.prepare()
            player.play()
            hooks.showing(copy)
        }
    }

    /** A channel wouldn't play: hide it if the proof is there, or wait for another channel's picture. */
    fun channelFailed(id: Long) {
        if (!hooks.settings().hideFailedOnPlay || hooks.casting()) return
        val now = System.currentTimeMillis()
        val proof = lastPicture?.let { (playedId, at) -> playedId != id && now - at < ChannelScan.OTHER_PLAYED_WITHIN_MS } == true
        if (proof) blame(listOf(id)) else awaitingProof[id] = now
    }

    /** A channel drew a picture: streams are flowing, so channels that failed shortly before can be judged. */
    fun pictureShown(id: Long) {
        val now = System.currentTimeMillis()
        lastPicture = id to now
        val due = awaitingProof.filter { (failed, at) -> failed != id && now - at < ChannelScan.OTHER_PLAYED_WITHIN_MS }.keys.toList()
        awaitingProof.clear()
        if (due.isNotEmpty()) blame(due)
    }

    private fun blame(ids: List<Long>) {
        container.appScope.launch {
            if (container.channelScan.channelToBlame(anotherChannelPlayed = true)) {
                ids.forEach { container.channelScan.hideAfterPlayFailure(it) }
            }
        }
    }

    fun release() {
        failover?.release()
    }
}
