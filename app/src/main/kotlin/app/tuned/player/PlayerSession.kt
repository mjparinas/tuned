package app.tuned.player

import android.app.PendingIntent
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import app.tuned.diagnostics.attempt

/**
 * The player's media session (docs/decisions/player.md): headphone and Bluetooth buttons, a TV remote's play and
 * pause keys, "Hey Google, pause", and the system's now-playing card all reach the player on screen. It wraps the
 * same player the controls use, so a button press does what the on-screen button does (casting included), and
 * next or previous changes channel on the one connection like the player's own buttons. It lives and dies with
 * the player screen. With background play on ([BackgroundPlay]) it also drives the media notification, through
 * [PlaybackService], so the sound can carry on while the screen is gone.
 */
class PlayerSession(
    private val context: Context,
    /** Settings › Player › Media buttons, read when the player starts. */
    private val enabled: () -> Boolean,
    /** Settings › Player › Keep playing in the background, read when the player starts. */
    private val background: () -> Boolean = { false },
    /** Where a tap on the media notification goes: back to this player. */
    private val sessionActivity: () -> PendingIntent? = { null },
) {
    private var session: MediaSession? = null

    /** Starts the session around [player], or hands an existing one the new player. */
    fun attach(player: Player) {
        if (!enabled() && !background()) return
        session?.let {
            it.player = player
            return
        }
        session =
            attempt(TAG, "Couldn't start the media session") {
                MediaSession
                    .Builder(context, player)
                    .setId(ID)
                    .apply { sessionActivity()?.let(::setSessionActivity) }
                    .build()
            }.getOrNull()
        current = session
        if (session != null && background()) PlaybackService.start(context)
    }

    fun release() {
        current = null
        session?.release()
        if (session != null && background()) PlaybackService.stop(context)
        session = null
    }

    companion object {
        private const val TAG = "PlayerSession"
        private const val ID = "tuned-player"

        /** The open player's session, which [PlaybackService] shows in the media notification. */
        @Volatile
        var current: MediaSession? = null
            private set
    }
}
