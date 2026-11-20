package com.tunedplayer.app.ui

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import com.tunedplayer.app.R
import com.tunedplayer.app.data.SyncProgress
import com.tunedplayer.app.data.SyncProgress.Step
import java.text.NumberFormat

/** The words for a running refresh: its steps for the first download, and one line for the lists and the notification. */
object SyncText {
    /** One line: "Step 2 of 6 · Processing channels · 45% · 12,345 items". */
    fun describe(
        context: Context,
        progress: SyncProgress,
    ): String = listOfNotNull(
        context.getString(R.string.sync_step_of, progress.number, progress.count),
        context.getString(name(progress.step)),
        detail(context, progress),
    ).joinToString(" · ")

    /** What a step is called, such as "Downloading channels". */
    @StringRes
    fun name(step: Step): Int = when (step) {
        Step.DownloadPlaylist -> R.string.sync_step_download_playlist
        Step.ReadPlaylist -> R.string.sync_step_read_playlist
        Step.SavePlaylist -> R.string.sync_step_save_playlist
        Step.Details -> R.string.sync_step_details
        Step.DownloadGuide -> R.string.sync_step_download_guide
        Step.ReadGuide -> R.string.sync_step_read_guide
        Step.SaveGuide -> R.string.sync_step_save_guide
        Step.Library -> R.string.sync_step_library
    }

    /**
     * How far the step has got, or null when there is nothing to count: the size downloaded and the full size when the
     * server gives it ("12.4 MB of 48 MB", otherwise "12.4 MB so far"), how far through the file reading is and how many
     * channels or programmes it has found, or how much of the cache is saved.
     */
    fun detail(
        context: Context,
        progress: SyncProgress,
    ): String? = when (progress.step) {
        Step.DownloadPlaylist, Step.DownloadGuide -> {
            when {
                progress.waiting -> context.getString(R.string.sync_waiting_server)
                progress.total > 0 -> context.getString(R.string.sync_size_of, size(context, progress.done), size(context, progress.total))
                else -> context.getString(R.string.sync_size_so_far, size(context, progress.done))
            }
        }

        Step.ReadPlaylist, Step.ReadGuide, Step.Library -> {
            val rows = progress.rows.takeIf { it >= 0 }?.let { rows(context, progress.step, it) }
            listOfNotNull(percent(progress), rows).joinToString(" · ").ifEmpty { null }
        }

        Step.SavePlaylist, Step.SaveGuide, Step.Details -> {
            percent(progress)
        }
    }

    private fun rows(
        context: Context,
        step: Step,
        n: Long,
    ): String {
        val plural = if (step == Step.ReadGuide) R.plurals.sync_programmes else R.plurals.sync_items
        return context.resources.getQuantityString(plural, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count(n))
    }

    private fun percent(progress: SyncProgress): String? =
        progress.fraction?.let { NumberFormat.getPercentInstance().format(it.toDouble()) }

    private fun size(
        context: Context,
        bytes: Long,
    ): String = Formatter.formatShortFileSize(context, bytes)

    private fun count(n: Long): String = NumberFormat.getIntegerInstance().format(n)
}
