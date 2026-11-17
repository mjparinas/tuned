package app.tuned.player

import android.app.AlertDialog
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import app.tuned.R
import app.tuned.core.SubtitleOffset

/**
 * Subtitle timing: moves the captions earlier or later when they don't match the voices. Media3 reads captions as the
 * stream loads, so every cue passes through [parsers], which adds the offset; changing it reloads the stream at the same
 * spot (the one connection is closed before the next opens) so the cues already read are read again. The offset lasts
 * until the player closes, since a bad match belongs to one file, not to every film.
 */
@OptIn(UnstableApi::class)
class SubtitleTiming {
    @Volatile
    var offsetMs = 0L
        private set

    val parsers: SubtitleParser.Factory = Shifting(DefaultSubtitleParserFactory()) { offsetMs }

    /** The dialog from the player's toolbar: four steps, Reset, and the stream reloads on Done. */
    fun pick(
        activity: ComponentActivity,
        player: ExoPlayer,
        say: (String) -> Unit,
    ) {
        if (player.currentTracks.groups.none { it.type == C.TRACK_TYPE_TEXT && it.isSelected }) {
            say(activity.getString(R.string.subtitle_timing_none))
            return
        }
        var pending = offsetMs
        val density = activity.resources.displayMetrics.density
        val label =
            TextView(activity).apply {
                gravity = Gravity.CENTER
                textSize = 18f
                setPadding(0, (16 * density).toInt(), 0, (8 * density).toInt())
            }
        val show = { label.text = describe(activity, pending) }
        show()
        val steps =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                for ((text, by) in STEPS) {
                    addView(
                        Button(activity).apply {
                            this.text = activity.getString(text)
                            setOnClickListener {
                                pending = SubtitleOffset.step(pending, by)
                                show()
                            }
                        },
                    )
                }
            }
        val content =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(steps)
            }
        AlertDialog
            .Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.subtitle_timing)
            .setView(content)
            .setNeutralButton(R.string.subtitle_timing_reset) { _, _ -> apply(player, 0) }
            .setPositiveButton(R.string.subtitle_timing_done) { _, _ -> apply(player, pending) }
            .show()
    }

    private fun apply(
        player: ExoPlayer,
        ms: Long,
    ) {
        if (ms == offsetMs) return
        offsetMs = ms
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        if (items.isEmpty()) return
        player.setMediaItems(items, player.currentMediaItemIndex, player.currentPosition)
        player.prepare()
    }

    private fun describe(
        activity: ComponentActivity,
        ms: Long,
    ): String = when {
        ms > 0 -> activity.getString(R.string.subtitle_timing_later, SubtitleOffset.seconds(ms))
        ms < 0 -> activity.getString(R.string.subtitle_timing_earlier, SubtitleOffset.seconds(ms))
        else -> activity.getString(R.string.subtitle_timing_in_sync)
    }

    private class Shifting(
        private val inner: SubtitleParser.Factory,
        private val offsetMs: () -> Long,
    ) : SubtitleParser.Factory {
        override fun supportsFormat(format: Format) = inner.supportsFormat(format)

        override fun getCueReplacementBehavior(format: Format) = inner.getCueReplacementBehavior(format)

        override fun create(format: Format): SubtitleParser {
            val parser = inner.create(format)
            return object : SubtitleParser {
                override fun parse(
                    data: ByteArray,
                    offset: Int,
                    length: Int,
                    outputOptions: SubtitleParser.OutputOptions,
                    output: Consumer<CuesWithTiming>,
                ) {
                    val ms = offsetMs()
                    parser.parse(data, offset, length, outputOptions) { cues ->
                        output.accept(
                            if (ms == 0L) {
                                cues
                            } else {
                                CuesWithTiming(cues.cues, SubtitleOffset.shift(cues.startTimeUs, ms, C.TIME_UNSET), cues.durationUs)
                            },
                        )
                    }
                }

                override fun getCueReplacementBehavior() = parser.cueReplacementBehavior

                override fun reset() = parser.reset()
            }
        }
    }

    private companion object {
        val STEPS =
            listOf(
                R.string.subtitle_timing_back_big to -SubtitleOffset.BIG_STEP_MS,
                R.string.subtitle_timing_back_small to -SubtitleOffset.SMALL_STEP_MS,
                R.string.subtitle_timing_on_small to SubtitleOffset.SMALL_STEP_MS,
                R.string.subtitle_timing_on_big to SubtitleOffset.BIG_STEP_MS,
            )
    }
}
