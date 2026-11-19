package app.tuned.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

/** The title above the line under it. */
internal fun titleColumn(
    context: Context,
    title: View,
    subtitle: View,
) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    addView(title)
    addView(subtitle)
}

/** The bar across the top on its scrim: back, then [titles] taking the room left; the tools and the clock join at the end. */
internal fun topBar(
    context: Context,
    back: View,
    titles: View,
) = LinearLayout(context).apply {
    val density = resources.displayMetrics.density
    orientation = LinearLayout.HORIZONTAL
    setBackgroundResource(R.drawable.player_top_scrim)
    val pad = (24 * density).toInt()
    setPadding(pad / 2, pad / 2, pad, pad * 2)
    addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (4 * density).toInt() })
    gravity = Gravity.CENTER_VERTICAL
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

/**
 * A control that can't work right now (most while casting, where the TV plays the video) stays in its place, greyed
 * out and disabled, rather than doing nothing or saying so in a message (Melvin, 2026-11-19: "Any functionality that
 * you cant use on the UI just disable them, and make them greyed out"). TalkBack reads it as disabled.
 */
internal fun View.usable(on: Boolean) {
    isEnabled = on
    alpha = if (on) 1f else DISABLED_ALPHA
}

/** Material's opacity for disabled content. */
private const val DISABLED_ALPHA = 0.38f

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
