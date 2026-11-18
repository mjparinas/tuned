package app.tuned.player

import app.tuned.core.RemoteKeys
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Channel numbers and flip back (Melvin, 2026-11-17, from the app audit), as on a cable box: type a number on the
 * remote and the player goes to that channel once the digits stop; the last-channel button goes back to the channel
 * before. Either way the player changes the one stream it has, so it never holds two connections.
 */
class ChannelDial(
    private val scope: CoroutineScope,
    private val hooks: Hooks,
) {
    class Hooks(
        /** The live channel numbered so, or null. */
        val find: suspend (Int) -> ItemEntity?,
        /** The channel by id, for flipping back. */
        val get: suspend (Long) -> ItemEntity?,
        /** Plays the channel in place of the current one. */
        val jump: suspend (ItemEntity) -> Unit,
        /** Shows a short line over the picture. */
        val say: (String) -> Unit,
        val noSuchChannel: (Int) -> String,
    )

    private var typed = ""
    private var waiting: Job? = null
    private var current: Long? = null
    private var previous: Long? = null

    /** A number key was pressed. */
    fun type(digit: Int) {
        typed = RemoteKeys.typeDigit(typed, digit)
        hooks.say(typed)
        waiting?.cancel()
        waiting =
            scope.launchSafely("ChannelDial") {
                delay(RemoteKeys.NUMBER_WAIT_MS)
                val number = typed.toIntOrNull()
                typed = ""
                if (number == null || number == 0) return@launchSafely
                val channel = hooks.find(number)
                if (channel == null) hooks.say(hooks.noSuchChannel(number)) else if (channel.id != current) hooks.jump(channel)
            }
    }

    /** The last-channel button: back to the channel watched before this one. */
    fun flipBack() {
        val id = previous ?: return
        scope.launchSafely("ChannelDial") { hooks.get(id)?.let { hooks.jump(it) } }
    }

    /** The player moved to live channel [id], by any means. */
    fun onChannel(id: Long) {
        if (id == current) return
        previous = current
        current = id
    }

    val canFlipBack: Boolean get() = previous != null

    fun release() {
        waiting?.cancel()
    }
}
