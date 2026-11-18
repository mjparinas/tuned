package app.tuned.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.tuned.R

// The player's plain views (title, status, back) and full-screen mode, kept out of PlayerActivity so it holds only
// the wiring between features.

internal fun titleText(context: Context) = TextView(context).apply {
    setTextColor(Color.WHITE)
    textSize = 18f
    setTypeface(typeface, Typeface.BOLD)
}

/** The line under the title (what's on, or the show and episode), gone while there is nothing to say. */
internal fun subtitleText(context: Context) = TextView(context).apply {
    setTextColor(0xCCFFFFFF.toInt())
    textSize = 14f
    visibility = View.GONE
    doAfterTextChanged { visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }
}

/** The centred message for holds and errors, hidden until there is one. */
internal fun statusText(context: Context) = TextView(context).apply {
    setTextColor(Color.WHITE)
    textSize = 14f
    visibility = View.GONE
    setBackgroundColor(0x99000000.toInt())
    val pad = (12 * resources.displayMetrics.density).toInt()
    setPadding(pad * 2, pad, pad * 2, pad)
}

internal fun backButton(
    context: Context,
    onBack: () -> Unit,
) = ImageButton(context).apply {
    setImageResource(R.drawable.ic_arrow_back)
    contentDescription = context.getString(R.string.back)
    val ripple = TypedValue()
    context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
    setBackgroundResource(ripple.resourceId)
    val pad = (12 * resources.displayMetrics.density).toInt()
    setPadding(pad, pad, pad, pad)
    setOnClickListener { onBack() }
}

/** Video fills the screen; a swipe from the edge shows the system bars for a moment. */
internal fun hideSystemBars(window: Window) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
