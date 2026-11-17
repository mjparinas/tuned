package app.tuned.ui

import android.content.Context
import android.text.format.Formatter
import app.tuned.R
import app.tuned.data.SyncProgress
import java.text.NumberFormat

/** One line about a running refresh, for the sign-in screen, the lists and the notification. */
object SyncText {
    fun describe(
        context: Context,
        progress: SyncProgress,
    ): String {
        val res = context.resources
        return when (progress.step) {
            SyncProgress.Step.Playlist -> {
                context.getString(R.string.sync_downloading_playlist, size(context, progress))
            }

            SyncProgress.Step.Guide -> {
                context.getString(R.string.sync_downloading_guide, size(context, progress))
            }

            SyncProgress.Step.SavingPlaylist -> {
                res.getQuantityString(R.plurals.sync_saving_playlist, progress.done.toQuantity(), count(progress.done))
            }

            SyncProgress.Step.SavingGuide -> {
                res.getQuantityString(R.plurals.sync_saving_guide, progress.done.toQuantity(), count(progress.done))
            }
        }
    }

    private fun size(
        context: Context,
        progress: SyncProgress,
    ): String {
        val done = Formatter.formatShortFileSize(context, progress.done)
        return if (progress.total > 0) {
            context.getString(R.string.sync_size_of, done, Formatter.formatShortFileSize(context, progress.total))
        } else {
            done
        }
    }

    private fun count(n: Long): String = NumberFormat.getIntegerInstance().format(n)

    private fun Long.toQuantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
