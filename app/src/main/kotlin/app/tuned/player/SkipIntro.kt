package app.tuned.player

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.tuned.R
import app.tuned.core.ContentKind
import app.tuned.core.IntroSkip
import app.tuned.data.IntroStore
import app.tuned.data.db.ItemEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The "Skip intro" button on episodes (see [IntroSkip]): it shows near the start, at the bottom right, and jumps past
 * the intro. A skip forward by hand near the start teaches Tuned where this show's intro is, for its next episodes.
 */
class SkipIntro(
    private val activity: ComponentActivity,
    private val player: () -> ExoPlayer?,
    private val current: () -> ItemEntity?,
    private val lengthMs: () -> Long,
) {
    private val store = IntroStore(activity.applicationContext)
    private val density = activity.resources.displayMetrics.density
    private var showKey: String? = null
    private var itemId: Long? = null
    private var intro: IntroSkip.Intro? = null

    /** What this episode has taught so far, so taps of skip-forward in a row add up. */
    private var learnedHere: IntroSkip.Intro? = null
    private var skipping = false

    private val button =
        TextView(activity).apply {
            text = activity.getString(R.string.skip_intro)
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
            setOnClickListener { skip() }
        }

    /** Learns from seeks the person makes; the button's own jump doesn't count. */
    val listener =
        object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK || oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
                if (skipping) {
                    skipping = false
                    return
                }
                val key = showKey ?: return
                val learned = IntroSkip.learn(oldPosition.positionMs, newPosition.positionMs, learnedHere) ?: return
                learnedHere = learned
                intro = learned
                activity.lifecycleScope.launch { store.set(key, learned) }
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
        activity.lifecycleScope.launch {
            while (isActive) {
                update()
                delay(CHECK_MS)
            }
        }
    }

    private suspend fun update() {
        val item = current()?.takeIf { ContentKind.fromCode(it.kind) == ContentKind.SERIES }
        val key = item?.showKey
        if (item?.id != itemId) {
            itemId = item?.id
            learnedHere = null
        }
        if (key != showKey) {
            showKey = key
            intro = null
            if (key != null) {
                val learned = store.get(key)
                if (key == showKey) intro = learned
            }
        }
        val p = player()
        val offer = IntroSkip.intro(intro, lengthMs())
        val show = key != null && p != null && p.isPlaying && IntroSkip.showButton(p.currentPosition, offer)
        button.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun skip() {
        val p = player() ?: return
        val offer = IntroSkip.intro(intro, lengthMs()) ?: return
        skipping = true
        p.seekTo(offer.endMs)
        button.visibility = View.GONE
    }

    private companion object {
        const val CHECK_MS = 500L
        const val BOTTOM_DP = 112
        const val BACKGROUND = 0x99000000.toInt()
    }
}
