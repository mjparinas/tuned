package app.tuned.player

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.tuned.R
import kotlin.math.roundToInt

/**
 * The brightness and volume levels while a swipe changes them (Melvin, 2026-11-17: "subtle bars on the left and right
 * side with a brightness and sound icon"): a thin bar with its icon at the edge of the side being swiped, brightness on
 * the left and volume on the right, matching where the finger is. It fades in at once and out shortly after the finger
 * lifts, and never covers the middle of the picture.
 */
class LevelBars(
    private val activity: Activity,
) {
    private val density = activity.resources.displayMetrics.density
    private val brightness = Column(activity, R.drawable.ic_player_brightness)
    private val volume = Column(activity, R.drawable.ic_player_volume)

    fun attach(root: FrameLayout) {
        val margin = (EDGE_DP * density).toInt()
        root.addView(
            brightness.view,
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.CENTER_VERTICAL,
                ).apply { marginStart = margin },
        )
        root.addView(
            volume.view,
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.CENTER_VERTICAL,
                ).apply { marginEnd = margin },
        )
    }

    fun showBrightness(level: Float) {
        brightness.show(level, activity.getString(R.string.gesture_brightness, percent(level)))
    }

    fun showVolume(level: Float) {
        volume.icon.setImageResource(if (level <= 0f) R.drawable.ic_player_volume_off else R.drawable.ic_player_volume)
        volume.show(level, activity.getString(R.string.gesture_volume, percent(level)))
    }

    /** After the finger lifts: both fade out after a moment. */
    fun hide() {
        brightness.hide()
        volume.hide()
    }

    private fun percent(level: Float) = (level * PERCENT).roundToInt()

    private inner class Column(
        context: Context,
        iconRes: Int,
    ) {
        val bar = Bar(context)
        val icon =
            ImageView(context).apply {
                setImageResource(iconRes)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        val view: LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                alpha = 0f
                visibility = View.GONE
                addView(bar, LinearLayout.LayoutParams((BAR_WIDTH_DP * density).toInt(), (BAR_HEIGHT_DP * density).toInt()))
                val size = (ICON_DP * density).toInt()
                addView(icon, LinearLayout.LayoutParams(size, size).apply { topMargin = (GAP_DP * density).toInt() })
            }

        fun show(
            level: Float,
            description: String,
        ) {
            bar.level = level
            bar.contentDescription = description
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view
                .animate()
                .alpha(1f)
                .setStartDelay(0)
                .setDuration(FADE_IN_MS)
                .start()
        }

        fun hide() {
            if (view.visibility != View.VISIBLE) return
            view
                .animate()
                .alpha(0f)
                .setStartDelay(LINGER_MS)
                .setDuration(FADE_OUT_MS)
                .withEndAction { view.visibility = View.GONE }
                .start()
        }
    }

    /** A rounded track with the level filled from the bottom. */
    private class Bar(
        context: Context,
    ) : View(context) {
        private val track =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = TRACK_ALPHA
            }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        var level = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = w / 2
            canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
            if (level > 0f) canvas.drawRoundRect(0f, h - h * level, w, h, r, r, fill)
        }
    }

    private companion object {
        const val EDGE_DP = 32
        const val BAR_WIDTH_DP = 4
        const val BAR_HEIGHT_DP = 140
        const val ICON_DP = 20
        const val GAP_DP = 12
        const val TRACK_ALPHA = 77 // 30% white
        const val PERCENT = 100
        const val FADE_IN_MS = 120L
        const val FADE_OUT_MS = 300L
        const val LINGER_MS = 600L
    }
}
