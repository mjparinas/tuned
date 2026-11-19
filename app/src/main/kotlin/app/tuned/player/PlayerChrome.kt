package app.tuned.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
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

/**
 * The centred message for holds and errors, hidden until there is one. While something is on its way (a cast
 * connecting to the TV, or starting there) a small spinner shows beside the words, so a tap that takes a few seconds
 * is seen to be working; the words say the same for anyone who can't see it.
 */
internal class StatusNote(
    context: Context,
) : LinearLayout(context) {
    private val words =
        TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
    private val spinner =
        ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** Setting the words on their own takes the spinner away; [show] keeps it for work under way. */
    var text: CharSequence
        get() = words.text
        set(value) {
            show(value, busy = false)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setBackgroundColor(0x99000000.toInt())
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad * 2, pad, pad * 2, pad)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val size = (20 * resources.displayMetrics.density).toInt()
        addView(spinner, LayoutParams(size, size).apply { marginEnd = pad })
        addView(words)
    }

    fun show(
        value: CharSequence,
        busy: Boolean,
    ) {
        words.text = value
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        visibility = View.VISIBLE
    }
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
