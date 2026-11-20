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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Auto-tune's "Next stream" button (Melvin, 2026-11-17: "there should be a go to the next one button that keeps going
 * just in case it's not the right one"). Auto-tune stops on the first channel that plays; this moves on to the next
 * one in its list, which auto-tune then tries as usual, skipping any that don't play. It shows with the controls, and
 * on its own for a few seconds once a channel is found, at the bottom right.
 */
internal class TuneNext(
    private val activity: ComponentActivity,
    /** True while auto-tune has another channel to try. */
    private val hasNext: () -> Boolean,
    private val onNext: () -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private var controls = false
    private var flashJob: Job? = null

    private val button =
        TextView(activity).apply {
            text = activity.getString(R.string.tune_next)
            setTextColor(Color.WHITE)
            textSize = 15f
            val padH = (20 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background =
                GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(BACKGROUND)
                    setStroke((1 * density).toInt(), Color.WHITE)
                }
            isFocusable = true
            isClickable = true
            visibility = View.GONE
            setOnClickListener {
                onNext()
                refresh()
            }
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
    }

    /** The player's controls showed or hid. */
    fun onControls(visibility: Int) {
        controls = visibility == View.VISIBLE
        refresh()
    }

    /** A channel was found: show the button for a moment so it's clear there are more to try. */
    fun flash() {
        flashJob?.cancel()
        flashJob =
            activity.lifecycleScope.launchSafely("TuneNext") {
                button.visibility = if (hasNext()) View.VISIBLE else View.GONE
                delay(FLASH_MS)
                flashJob = null
                refresh()
            }
    }

    private fun refresh() {
        if (flashJob != null && !controls) return
        button.visibility = if (controls && hasNext()) View.VISIBLE else View.GONE
    }

    private companion object {
        const val FLASH_MS = 5_000L
        const val BOTTOM_DP = 112
        const val BACKGROUND = 0x99000000.toInt()
    }
}
