package app.tuned.player

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager

/**
 * How long the player's controls stay up after a tap. A screen reader (TalkBack) moves through the buttons one swipe at
 * a time, so the usual few seconds hide them mid-way: with one on, the controls stay until the video is tapped again.
 * Otherwise Android's "Time to take action" setting can lengthen the chosen time, never shorten it.
 *
 * Media3 keeps the controls up for good when the timeout is zero or less.
 */
object AccessibleControls {
    const val STAY = 0

    fun timeoutMs(
        context: Context,
        chosenMs: Int,
    ): Int {
        val a11y = context.getSystemService(AccessibilityManager::class.java) ?: return chosenMs
        if (a11y.isEnabled && a11y.isTouchExplorationEnabled) return STAY
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return chosenMs
        val flags =
            AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_TEXT
        return a11y.getRecommendedTimeoutMillis(chosenMs, flags).coerceAtLeast(chosenMs)
    }
}
