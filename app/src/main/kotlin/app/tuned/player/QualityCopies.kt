package app.tuned.player

import android.app.AlertDialog
import android.content.Context
import app.tuned.R
import app.tuned.core.ChannelCopies
import app.tuned.data.TuneRepository
import app.tuned.diagnostics.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * The HQ button's copies (Melvin, 2026-11-18: "does any stream have an hq version? otherwise the hq button is
 * useless"). Providers list a live channel once per picture quality ("US| ESPN HD", "US| ESPN FHD", "US| ESPN 4K"),
 * so for the channel playing this finds the best copy of each other quality, and the button switches to one. The old
 * stream closes before the copy opens ([Backups.swapTo]), so there is still one connection.
 */
class QualityCopies(
    private val scope: CoroutineScope,
    private val tune: TuneRepository,
    private val hooks: Hooks,
) {
    class Hooks(
        val casting: () -> Boolean,
        /** Puts the copy with this id in the current spot and starts it. */
        val switchTo: (Long) -> Unit,
        /** The copies changed, so the button may show or hide. */
        val changed: () -> Unit,
    )

    private var copies: List<TuneRepository.QualityCopy> = emptyList()
    private var current: Long? = null
    private var lookup: Job? = null

    /** There is another quality to switch to. */
    val available: Boolean get() = exist && !hooks.casting()

    /** The channel has copies in other qualities, whether or not they can be switched to now (not while casting). */
    val exist: Boolean get() = copies.size > 1

    /** A live channel started playing (null when what plays is not one): look up its copies. */
    fun onChannel(id: Long?) {
        if (id == current) return
        current = id
        copies = emptyList()
        hooks.changed()
        lookup?.cancel()
        if (id == null) return
        lookup =
            scope.launchSafely("QualityCopies") {
                val found = tune.qualityCopies(id)
                if (current == id) {
                    copies = found
                    hooks.changed()
                }
            }
    }

    fun pick(context: Context) {
        val options = copies
        if (options.size < 2) return
        val labels = options.map { context.getString(R.string.player_quality_copy, label(context, it.picture), it.name) }
        AlertDialog
            .Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.player_quality)
            .setSingleChoiceItems(labels.toTypedArray(), options.indexOfFirst { it.id == current }) { dialog, which ->
                val chosen = options[which]
                dialog.dismiss()
                if (chosen.id != current) hooks.switchTo(chosen.id)
            }.show()
    }

    private fun label(
        context: Context,
        picture: Int,
    ): String = context.getString(
        when (picture) {
            ChannelCopies.UHD -> R.string.player_quality_uhd
            ChannelCopies.FHD -> R.string.player_quality_fhd
            ChannelCopies.HD -> R.string.player_quality_hd
            ChannelCopies.SD -> R.string.player_quality_sd
            else -> R.string.player_quality_untagged
        },
    )
}
