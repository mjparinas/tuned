package com.tunedplayer.app.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tunedplayer.app.R
import com.tunedplayer.app.diagnostics.launchSafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The "Live" button (Melvin, 2026-11-18: "since we can scrub through live tv and go back in time, there should be a
 * jump to live button"). It shows with the controls while a live channel plays from the copy on the device, behind
 * what is airing, and a tap goes back to the channel as it airs ([LiveTimeshift.goLive]).
 */
class JumpToLive(
    private val activity: ComponentActivity,
    /** Behind live right now: paused into, or rewound in, the copy on the device. */
    private val behind: () -> Boolean,
    /** Goes back to live; false if the player wasn't behind. */
    private val goLive: () -> Boolean,
) {
    private val density = activity.resources.displayMetrics.density
    private var controls = false
    private var hidden = false

    private val button =
        TextView(activity).apply {
            text = activity.getString(R.string.jump_to_live)
            contentDescription = activity.getString(R.string.jump_to_live_description)
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val padH = (16 * density).toInt()
            val padV = (8 * density).toInt()
            setPadding(padH, padV, padH, padV)
            val dot = (8 * density).toInt()
            setCompoundDrawables(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(LIVE_RED)
                    setSize(dot, dot)
                    setBounds(0, 0, dot, dot)
                },
                null,
                null,
                null,
            )
            compoundDrawablePadding = (8 * density).toInt()
            gravity = Gravity.CENTER_VERTICAL
            background =
                GradientDrawable().apply {
                    cornerRadius = 100 * density
                    setColor(BACKGROUND)
                    setStroke((1 * density).toInt(), Color.WHITE)
                }
            isFocusable = true
            isClickable = true
            visibility = View.GONE
            setOnClickListener { if (goLive()) visibility = View.GONE }
        }

    fun attach(root: FrameLayout) {
        root.addView(
            button,
            FrameLayout
                .LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                .apply {
                    marginEnd = (24 * density).toInt()
                    bottomMargin = (BOTTOM_DP * density).toInt()
                },
        )
        // Pausing switches to the copy on the device after the controls have already shown, so this keeps up with it.
        activity.lifecycleScope.launchSafely("JumpToLive") {
            while (isActive) {
                update()
                delay(CHECK_MS)
            }
        }
    }

    /** Shows and hides with the player's controls, so the video is otherwise bare. */
    fun onControls(visibility: Int) {
        controls = visibility == View.VISIBLE
        update()
    }

    /** Picture-in-picture: nothing over the video. */
    fun hide(hide: Boolean) {
        hidden = hide
        update()
    }

    private fun update() {
        button.visibility = if (controls && !hidden && behind()) View.VISIBLE else View.GONE
    }

    private companion object {
        const val CHECK_MS = 500L

        /** Just above the seek bar, where Skip intro sits on episodes (it never shows on live channels). */
        const val BOTTOM_DP = 112
        const val BACKGROUND = 0x99000000.toInt()
        const val LIVE_RED = 0xFFE53935.toInt()
    }
}
