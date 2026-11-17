package app.tuned.player

import android.app.Activity
import android.os.Build
import android.view.Display
import app.tuned.core.FrameRates

/**
 * Settings › Player › Match frame rate (2026-11-17, from the app audit). When the video's frame rate is known, the
 * window asks for the display mode at the same resolution whose refresh rate fits it ([FrameRates.pick]), so 50 fps
 * sport plays at 50 Hz and films at 24 Hz. The request belongs to the player's window, so the TV goes back to its usual
 * mode as soon as the player closes. Phones and TVs without such modes simply stay as they are.
 */
internal class FrameRateMatch(
    private val activity: Activity,
    private val enabled: () -> Boolean,
) {
    private var matchedFps = 0f

    fun onVideo(fps: Float?) {
        if (!enabled() || fps == null || fps <= 0f || fps == matchedFps) return
        val display = display() ?: return
        val current = display.mode
        val modes =
            display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .map { FrameRates.Mode(it.modeId, it.refreshRate) }
        matchedFps = fps
        val pick = FrameRates.pick(fps, modes, FrameRates.Mode(current.modeId, current.refreshRate)) ?: return
        val window = activity.window
        window.attributes = window.attributes.also { it.preferredDisplayModeId = pick.id }
    }

    private fun display(): Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.display
    } else {
        @Suppress("DEPRECATION") // the only way to reach the display before Android 11
        activity.windowManager.defaultDisplay
    }
}
