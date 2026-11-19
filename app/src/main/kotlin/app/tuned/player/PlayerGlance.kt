package app.tuned.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.graphics.ColorUtils
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import app.tuned.R
import java.util.Locale

// What the player shows at a glance with its controls (Melvin, 2026-11-19, from another player's look: "scrubber with
// blue highlights (accent colour), I like the current time (don't need the day of the week or date), I also like the
// pills with the resolution, fps and sound codec"): the seek bar in the app's accent, the time of day at the top right
// and the stream's pills under the title. All of it shows and hides with the controls.

/** The time of day, "10:14 PM" or "22:14" as the device is set, with no day or date. It ticks only while shown. */
internal fun playerClock(context: Context): TextClock = TextClock(context).apply {
    val locale = Locale.getDefault()
    format12Hour = DateFormat.getBestDateTimePattern(locale, "hmma")
    format24Hour = DateFormat.getBestDateTimePattern(locale, "Hmm")
    setTextColor(Color.WHITE)
    textSize = 16f
    setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER_VERTICAL
    setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
}

/** Colours Media3's seek bar: the played part and its thumb in [accent], what has loaded a paler shade of it. */
@OptIn(UnstableApi::class)
internal fun tintScrubber(
    playerView: PlayerView,
    accent: Int,
) {
    val bar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress) as? DefaultTimeBar ?: return
    bar.setPlayedColor(accent)
    bar.setScrubberColor(accent)
    bar.setBufferedColor(ColorUtils.setAlphaComponent(accent, BUFFERED_ALPHA))
}

private const val BUFFERED_ALPHA = 0x66

/** Puts the pills under the title in [titles] and the clock at the end of the top bar, after the tools. */
internal fun addGlance(
    overlay: LinearLayout,
    titles: LinearLayout,
    pills: InfoPills,
) {
    val density = overlay.resources.displayMetrics.density
    val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
    titles.addView(pills.view, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = (6 * density).toInt() })
    overlay.addView(playerClock(overlay.context), LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = (12 * density).toInt() })
}

/**
 * The stream's picture size, frame rate, sound codec and speakers ("1920x1080", "24 FPS", "AAC", "STEREO"), read from
 * what ExoPlayer is playing. A pill whose value the stream doesn't give is left out, and the row is gone when none
 * is known or while casting, where the phone plays nothing.
 */
@OptIn(UnstableApi::class)
class InfoPills(
    private val context: Context,
    private val player: () -> ExoPlayer?,
    private val casting: () -> Boolean,
) {
    private val density = context.resources.displayMetrics.density

    val view: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

    /** What the row shows now, so an unchanged stream doesn't rebuild it. */
    private var shown: List<String>? = null

    /** Looks again when the tracks, the picture size or the item change. */
    val listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (WATCHED.any(events::contains)) update()
            }
        }

    fun update() {
        val p = player()
        if (p == null || casting()) {
            show(emptyList())
            return
        }
        val video = p.videoFormat
        val audio = p.audioFormat
        val size = p.videoSize
        val pills =
            StreamInfo.pills(
                width = size.width.takeIf { it > 0 } ?: video?.width ?: Format.NO_VALUE,
                height = size.height.takeIf { it > 0 } ?: video?.height ?: Format.NO_VALUE,
                frameRate = video?.frameRate ?: Format.NO_VALUE.toFloat(),
                audioMimeType = audio?.sampleMimeType,
                channels = audio?.channelCount ?: Format.NO_VALUE,
            )
        show(pills)
    }

    /** Shows [pills], leaving out what is unknown (the screenshots call this with made-up values). */
    internal fun show(pills: StreamInfo.Pills) = show(labels(pills))

    /** Each pill's text and what TalkBack says for it. */
    private fun labels(pills: StreamInfo.Pills): List<Pair<String, String?>> {
        val res = context.resources
        val speakers =
            when (pills.speakers) {
                null -> {
                    null
                }

                StreamInfo.Speakers.MONO -> {
                    res.getString(R.string.player_pill_mono) to res.getString(R.string.player_pill_mono_spoken)
                }

                StreamInfo.Speakers.STEREO -> {
                    res.getString(R.string.player_pill_stereo) to res.getString(R.string.player_pill_stereo_spoken)
                }

                StreamInfo.Speakers.SURROUND_5_1 -> {
                    res.getString(R.string.player_pill_5_1) to res.getString(R.string.player_pill_5_1_spoken)
                }

                StreamInfo.Speakers.SURROUND_7_1 -> {
                    res.getString(R.string.player_pill_7_1) to res.getString(R.string.player_pill_7_1_spoken)
                }

                StreamInfo.Speakers.OTHER -> {
                    res.getString(R.string.player_pill_channels, pills.channels) to
                        res.getQuantityString(R.plurals.player_pill_channels_spoken, pills.channels, pills.channels)
                }
            }
        return listOfNotNull(
            pills.resolution?.let { it to null },
            pills.frameRate?.let {
                res.getString(R.string.player_pill_fps, it) to res.getQuantityString(R.plurals.player_pill_fps_spoken, it, it)
            },
            pills.codec?.let { it to null },
            speakers,
        )
    }

    private fun show(labels: List<Pair<String, String?>>) {
        val texts = labels.map { it.first }
        if (texts == shown) return
        shown = texts
        view.removeAllViews()
        labels.forEachIndexed { i, (text, spoken) ->
            view.addView(
                pill(text, spoken),
                LinearLayout
                    .LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (i > 0) marginStart = (6 * density).toInt() },
            )
        }
        view.visibility = if (labels.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun pill(
        text: String,
        spoken: String?,
    ) = TextView(context).apply {
        this.text = text
        spoken?.let { contentDescription = it }
        setTextColor(Color.WHITE)
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.04f
        val padH = (7 * density).toInt()
        val padV = (2 * density).toInt()
        setPadding(padH, padV, padH, padV)
        background =
            GradientDrawable().apply {
                cornerRadius = 4 * density
                setColor(0x33FFFFFF)
                setStroke(density.toInt().coerceAtLeast(1), 0x66FFFFFF)
            }
    }

    private companion object {
        val WATCHED =
            intArrayOf(
                Player.EVENT_TRACKS_CHANGED,
                Player.EVENT_VIDEO_SIZE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_RENDERED_FIRST_FRAME,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
            )
    }
}
