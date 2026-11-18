package app.tuned.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.tuned.core.ContentKind
import app.tuned.core.Trakt
import app.tuned.data.TraktAccount
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Tells Trakt what's playing ([TraktAccount.scrobble]): start when a film or episode plays, pause when the person
 * pauses, stop when it ends, another starts or the player closes. Trakt marks it watched from 80%. Live TV, catch-up
 * and recordings aren't sent, and nothing is when no Trakt account is signed in. Buffering isn't a pause: only the
 * person's play and pause count. Messages go out one after another, in order, from the app's scope, so the last stop
 * still arrives after the player closes.
 */
internal class TraktScrobbler(
    private val account: TraktAccount,
    private val scope: CoroutineScope,
    /** The item the player is on now. */
    private val current: () -> ItemEntity?,
) : Player.Listener {
    private var player: Player? = null
    private var started: Trakt.Target? = null
    private var lastProgress = 0.0
    private var last: Job? = null

    private val active: Boolean get() = account.available && account.session.value != null

    fun attach(p: Player) {
        player?.removeListener(this)
        player = p
        started = null
        if (active) p.addListener(this)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val p = player ?: return
        when (playbackState) {
            Player.STATE_READY -> if (p.playWhenReady && started == null) start(p)
            Player.STATE_ENDED -> stop(FULL)
            else -> Unit
        }
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        val p = player ?: return
        if (playWhenReady) {
            if (p.playbackState == Player.STATE_READY) start(p)
        } else {
            started?.let { send("pause", it, progress(p)) }
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        // The one before finished on its own, or the person moved on from it.
        stop(if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) FULL else lastProgress)
    }

    /** The player is closing: the last spot goes to Trakt. */
    fun release() {
        val p = player ?: return
        stop(progress(p))
        p.removeListener(this)
        player = null
    }

    private fun start(p: Player) {
        val item = current()?.takeIf { it.id != CatchUpLinks.ID } ?: return
        val target = Trakt.target(ContentKind.fromCode(item.kind), item.name, item.showName, item.season, item.episode) ?: return
        started = target
        send("start", target, progress(p))
    }

    private fun stop(progress: Double) {
        val target = started ?: return
        started = null
        send("stop", target, progress)
    }

    private fun progress(p: Player): Double = Trakt.progress(p.currentPosition, p.duration).also { lastProgress = it }

    private fun send(
        action: String,
        target: Trakt.Target,
        progress: Double,
    ) {
        val before = last
        last =
            scope.launchSafely(TAG) {
                before?.join()
                account.scrobble(action, target, progress)
            }
    }

    private companion object {
        const val TAG = "TraktScrobbler"
        const val FULL = 100.0
    }
}
