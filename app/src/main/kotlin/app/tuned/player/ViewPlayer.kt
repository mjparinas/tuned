package app.tuned.player

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackParameters
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
    /** Playing a live channel: no playback speed (Melvin, 2026-11-19: "remove that for live tv"). */
    private val live: () -> Boolean = { false },
) : ForwardingPlayer(exo) {
    private val casting get() = cast()?.casting == true

    /** Films, episodes, catch-up and recordings can play faster or slower; live TV and a cast to a TV can't. */
    private val speedAllowed get() = !live() && !casting

    override fun isCommandAvailable(command: Int): Boolean =
        (command != Player.COMMAND_SET_SPEED_AND_PITCH || speedAllowed) && super.isCommandAvailable(command)

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        return if (speedAllowed) commands else commands.buildUpon().remove(Player.COMMAND_SET_SPEED_AND_PITCH).build()
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (speedAllowed) super.setPlaybackSpeed(speed)
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (speedAllowed) super.setPlaybackParameters(playbackParameters)
    }

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
