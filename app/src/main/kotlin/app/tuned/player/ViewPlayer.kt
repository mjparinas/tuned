package app.tuned.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * What the on-screen controls drive: the phone's player, or the TV's play and pause while casting. Moved out of
 * `PlayerActivity`, which passes in what it knows.
 */
@OptIn(UnstableApi::class)
class ViewPlayer(
    private val exo: ExoPlayer,
    private val cast: () -> CastControl?,
    /** Play pressed while held (the account was full) or stopped: hide the status note before starting. */
    private val onStartHeld: () -> Unit,
    /** A live channel pauses into the copy kept on the device; true when it did. */
    private val pauseLive: (ExoPlayer) -> Boolean,
) : ForwardingPlayer(exo) {
    private val casting get() = cast()?.casting == true

    override fun prepare() {
        if (!casting) super.prepare()
    }

    override fun play() {
        when {
            casting -> {
                cast()?.play()
            }

            playbackState == Player.STATE_IDLE -> {
                onStartHeld()
                super.prepare()
                super.play()
            }

            else -> {
                super.play()
            }
        }
    }

    override fun pause() {
        when {
            casting -> cast()?.pause()

            // A live channel pauses into the copy kept on the device, so it can carry on, rewind or catch up.
            pauseLive(exo) -> Unit

            else -> super.pause()
        }
    }
}
