package app.tuned.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import app.tuned.diagnostics.attempt

/**
 * The player's media session (docs/decisions/player.md): headphone and Bluetooth buttons, a TV remote's play and
 * pause keys, "Hey Google, pause", and the system's now-playing card all reach the player on screen. It wraps the
 * same player the controls use, so a button press does what the on-screen button does (casting included), and
 * next or previous changes channel on the one connection like the player's own buttons. It lives and dies with
 * the player screen; nothing plays in the background.
 */
class PlayerSession(
    private val context: Context,
    /** Settings › Player › Media buttons, read when the player starts. */
    private val enabled: () -> Boolean,
) {
    private var session: MediaSession? = null

    /** Starts the session around [player], or hands an existing one the new player. */
    fun attach(player: Player) {
        if (!enabled()) return
        session?.let {
            it.player = player
            return
        }
        session =
            attempt(TAG, "Couldn't start the media session") {
                MediaSession.Builder(context, player).setId(ID).build()
            }.getOrNull()
    }

    fun release() {
        session?.release()
        session = null
    }

    private companion object {
        const val TAG = "PlayerSession"
        const val ID = "tuned-player"
    }
}
