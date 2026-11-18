package app.tuned.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Touch gestures over the video, the way most phone players work:
 * - drag up or down on the left half for brightness, on the right half for volume;
 * - double-tap the left or right third to skip back or forward (films and episodes);
 * - swipe left or right to change channel (live TV);
 * - a single tap still shows or hides the controls.
 *
 * A tap toggles the controls once, when it is confirmed as a single tap. [PlayerView.performClick] toggles them too, so
 * it is called only then and never on every finger lift: calling it on lift as well hid the controls and showed them
 * again a moment later (Melvin, 2026-11-18: "it looks like it's going to hide then it pops back up again").
 *
 * Brightness and volume show as bars at the screen's edges ([LevelBars]), which fade once the finger lifts.
 */
@OptIn(UnstableApi::class)
class PlayerGestures(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val isLive: () -> Boolean,
    private val onSeek: (forward: Boolean) -> Unit,
    private val onChannel: (next: Boolean) -> Unit,
    private val bars: LevelBars,
) : View.OnTouchListener {
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mode = Mode.None
    private var startLevel = 0f

    private enum class Mode { None, Brightness, Volume }

    private val detector =
        GestureDetector(
            activity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                // PlayerView's click toggles the controls, and calling it also tells accessibility services a click happened.
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean = playerView.performClick()

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isLive()) return false
                    val third = playerView.width / THIRDS
                    when {
                        e.x < third -> onSeek(false)
                        e.x > playerView.width - third -> onSeek(true)
                        else -> return false
                    }
                    return true
                }

                override fun onScroll(
                    down: MotionEvent?,
                    e: MotionEvent,
                    dx: Float,
                    dy: Float,
                ): Boolean {
                    val start = down ?: return false
                    val totalX = e.x - start.x
                    val totalY = e.y - start.y
                    if (mode == Mode.None) {
                        if (abs(totalY) < slop || abs(totalY) < abs(totalX)) return false
                        mode = if (start.x < playerView.width / 2f) Mode.Brightness else Mode.Volume
                        startLevel = if (mode == Mode.Brightness) brightness() else volume()
                    }
                    // A full-height drag goes from nothing to everything.
                    val level = (startLevel - totalY / playerView.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    if (mode == Mode.Brightness) setBrightness(level) else setVolume(level)
                    return true
                }

                override fun onFling(
                    down: MotionEvent?,
                    e: MotionEvent,
                    vx: Float,
                    vy: Float,
                ): Boolean {
                    val start = down ?: return false
                    if (mode != Mode.None || !isLive()) return false
                    val dx = e.x - start.x
                    if (abs(dx) < playerView.width / FLING_FRACTION || abs(vx) < abs(vy)) return false
                    onChannel(dx < 0)
                    return true
                }
            },
        )

    private val slop = 24 * activity.resources.displayMetrics.density

    // The click is performed once a single tap is confirmed (above), not on every lift, so a tap toggles the controls once.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(
        v: View,
        event: MotionEvent,
    ): Boolean {
        val handled = detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (mode != Mode.None) bars.hide()
            mode = Mode.None
        }
        return handled
    }

    private fun brightness(): Float {
        val current = activity.window.attributes.screenBrightness
        return if (current >= 0f) current else DEFAULT_BRIGHTNESS
    }

    private fun setBrightness(level: Float) {
        val attrs = activity.window.attributes
        attrs.screenBrightness = level.coerceAtLeast(MIN_BRIGHTNESS)
        activity.window.attributes = attrs
        bars.showBrightness(level)
    }

    private fun volume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun setVolume(level: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
        bars.showVolume(level)
    }

    /** Hands brightness back to the system when the player closes. */
    fun release() {
        val attrs = activity.window.attributes
        attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = attrs
    }

    private companion object {
        const val THIRDS = 3
        const val FLING_FRACTION = 4
        const val DEFAULT_BRIGHTNESS = 0.5f
        const val MIN_BRIGHTNESS = 0.01f
    }
}
