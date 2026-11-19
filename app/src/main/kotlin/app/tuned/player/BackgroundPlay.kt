package app.tuned.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Keep playing in the background (Melvin, 2026-11-19: "do 3, but have a setting that can toggle this off"). Leaving
 * the app or locking the screen used to end playback; now the sound carries on, with Android's media notification
 * to pause, play or change channel, and a tap on it goes back to the player. It is still the one player and the one
 * connection: nothing new opens, and downloads keep waiting because the player is still open. Closing the
 * picture-in-picture window still stops it, and so does a pause left for [IDLE_MS], so a forgotten pause doesn't
 * hold the provider's connection. Settings › Player › Keep playing in the background turns it off.
 */
class BackgroundPlay(
    private val activity: Activity,
    private val scope: CoroutineScope,
    /** The setting, read when the player starts. */
    private val enabled: () -> Boolean,
    private val player: () -> Player?,
    private val casting: () -> Boolean,
) {
    /** The screen is gone and the sound carries on. */
    var unseen = false
        private set
    private var idleJob: Job? = null

    /** Watches for a pause while unseen. */
    val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (unseen) watchIdle(isPlaying)
            }
        }

    /**
     * Called as the player screen stops: true when playback should carry on unseen instead of ending. [inPictureInPicture]
     * with the screen still on means the person closed the small window, which ends playback as before; with the screen
     * off it was locked while the window showed, which carries on.
     */
    fun keepPlaying(inPictureInPicture: Boolean): Boolean {
        val p = player() ?: return false
        if (!enabled() || casting() || !p.playWhenReady) return false
        if (inPictureInPicture && screenOn()) return false
        unseen = true
        watchIdle(p.isPlaying)
        return true
    }

    /** Back on screen. */
    fun onStart() {
        unseen = false
        idleJob?.cancel()
    }

    fun release() {
        idleJob?.cancel()
    }

    private fun watchIdle(playing: Boolean) {
        idleJob?.cancel()
        if (playing) return
        idleJob =
            scope.launchSafely(TAG) {
                delay(IDLE_MS)
                // Paused and out of sight for a while: close the player, which hangs up the stream.
                activity.finish()
            }
    }

    private fun screenOn(): Boolean = (activity.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false

    companion object {
        private const val TAG = "BackgroundPlay"

        /** How long a pause may last unseen before the player closes. */
        const val IDLE_MS = 10 * 60_000L
    }
}

/**
 * Android's media notification and foreground service for [BackgroundPlay]: without them Android stops a player it
 * can't see. It holds no player of its own; it shows the player screen's session ([PlayerSession.current]).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        PlayerSession.current?.let { attempt(TAG, "Couldn't show the media notification") { addSession(it) } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = PlayerSession.current

    /** Swiping Tuned away from recent apps ends playback, as closing the player does. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Stop rather than pause: a paused live channel keeps receiving into its rewind copy.
        PlayerSession.current?.player?.stop()
        pauseAllPlayersAndStopSelf()
    }

    companion object {
        private const val TAG = "PlaybackService"

        /** Started while the player is on screen, so Android lets it carry on once the screen goes. */
        fun start(context: Context) {
            attempt(TAG, "Couldn't start background playback") { context.startService(Intent(context, PlaybackService::class.java)) }
        }

        fun stop(context: Context) {
            attempt(TAG, "Couldn't stop background playback") { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
