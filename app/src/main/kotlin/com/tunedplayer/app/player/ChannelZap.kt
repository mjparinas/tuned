package com.tunedplayer.app.player

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.target.ImageViewTarget
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.ChannelFlips
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.Titles
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.diagnostics.SpeedTrace
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Faster channel zapping (Melvin, 2026-11-28). The moment a live channel is picked or flipped to, a card at the lower
 * left shows its logo, number and name (and what's on, once the guide answers), so the screen answers the press
 * before the stream has said a word; it goes a moment after the picture comes. Flips in quick succession move only
 * the card, and the stream changes once, where they stop ([ChannelFlips]), so the channels passed over never open a
 * connection. The player still has one stream: the old one closes as the new one opens, never prefetched or
 * buffered ahead (docs/decisions/scope.md).
 */
internal class ChannelZap(
    private val activity: ComponentActivity,
    private val queue: () -> List<ItemEntity>,
    private val casting: () -> Boolean,
) {
    private val density = activity.resources.displayMetrics.density
    private val flips = ChannelFlips()
    private var settleJob: Job? = null
    private var hideJob: Job? = null
    private var guideJob: Job? = null

    /** When the last change of channel was asked for, to time it to the picture (Settings' speed trace). */
    private var askedAt = 0L

    private val logo =
        ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    private val name =
        TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    private val now =
        TextView(activity).apply {
            setTextColor(NOW_COLOUR)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
    private val card =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad * 2, pad)
            background =
                GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(CARD_COLOUR)
                }
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            val size = (LOGO_DP * density).toInt()
            addView(logo, LinearLayout.LayoutParams(size, size).apply { marginEnd = pad })
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(name)
                    addView(now)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

    fun attach(root: FrameLayout) {
        root.addView(
            card,
            FrameLayout
                .LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                .apply {
                    marginStart = (EDGE_DP * density).toInt()
                    bottomMargin = (BOTTOM_DP * density).toInt()
                    marginEnd = (EDGE_DP * density).toInt()
                },
        )
    }

    /** On the player: the card for each new live channel, and the timing to its first picture. */
    val listener =
        object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                val id = mediaItem?.mediaId?.toLongOrNull() ?: return
                // Opened from a list or a number typed: timed from here. A flip already noted when it was pressed.
                if (askedAt == 0L) askedAt = SystemClock.elapsedRealtime()
                // While quick flips wait, the card already shows where they have got to.
                if (flips.waiting == null) queue().firstOrNull { it.id == id }?.let(::show)
            }

            override fun onRenderedFirstFrame() {
                if (askedAt > 0) SpeedTrace.took("Channel change to picture", SystemClock.elapsedRealtime() - askedAt)
                askedAt = 0
                if (flips.waiting == null) hideAfter(AFTER_PICTURE_MS)
            }
        }

    /**
     * Channel up or down on [exo], from the remote, a swipe or the on-screen buttons. False when it isn't a live
     * channel's flip, so the player does what it always did.
     */
    fun flip(
        exo: Player,
        next: Boolean,
    ): Boolean {
        val items = queue()
        if (casting() || items.size < 2) return false
        val timeline = exo.currentTimeline
        if (timeline.isEmpty) return false
        val from = flips.from(exo.currentMediaItemIndex)
        val to =
            if (next) {
                timeline.getNextWindowIndex(from, exo.repeatMode, exo.shuffleModeEnabled)
            } else {
                timeline.getPreviousWindowIndex(from, exo.repeatMode, exo.shuffleModeEnabled)
            }
        if (to == C.INDEX_UNSET) return true // the end of the group: nothing to flip to, as before
        val item = items.getOrNull(to) ?: return false
        if (item.kind != ContentKind.LIVE.code) return false
        show(item)
        askedAt = SystemClock.elapsedRealtime()
        if (flips.flip(to, askedAt)) {
            exo.seekTo(to, C.TIME_UNSET)
        } else {
            settleJob?.cancel()
            settleJob =
                activity.lifecycleScope.launchSafely(TAG) {
                    delay(flips.settleMs)
                    val target = flips.settled() ?: return@launchSafely
                    askedAt = SystemClock.elapsedRealtime()
                    if (target != exo.currentMediaItemIndex) exo.seekTo(target, C.TIME_UNSET) else hideAfter(AFTER_PICTURE_MS)
                }
        }
        return true
    }

    private fun show(item: ItemEntity) {
        if (casting() || item.kind != ContentKind.LIVE.code || activity.isInPictureInPictureMode) return
        val shown = Titles.display(item.name)
        name.text = if (item.number > 0) activity.getString(R.string.player_zap_channel, item.number, shown) else shown
        now.visibility = View.GONE
        logo.visibility = if (item.logo.isNullOrBlank()) View.GONE else View.VISIBLE
        item.logo?.takeIf { it.isNotBlank() }?.let { url ->
            attempt(TAG, "Couldn't load a channel logo") {
                SingletonImageLoader.get(activity).enqueue(ImageRequest.Builder(activity).data(url).target(ImageViewTarget(logo)).build())
            }
        }
        card.visibility = View.VISIBLE
        // Up until a picture comes, and never for long if one doesn't.
        hideAfter(LONGEST_MS)
        guideJob?.cancel()
        val tvgId = item.tvgId ?: return
        guideJob =
            activity.lifecycleScope.launchSafely(TAG) {
                val at = System.currentTimeMillis()
                val on =
                    activity.container.db
                        .programmes()
                        .nowAndNext(tvgId, at)
                        .firstOrNull { it.start <= at }
                if (on != null) {
                    now.text = activity.getString(R.string.player_now, on.title)
                    now.visibility = View.VISIBLE
                }
            }
    }

    private fun hideAfter(ms: Long) {
        hideJob?.cancel()
        hideJob =
            activity.lifecycleScope.launchSafely(TAG) {
                delay(ms)
                card.visibility = View.GONE
            }
    }

    /** The controls came up: they show the channel's name themselves. */
    fun onControls(visibility: Int) {
        if (visibility == View.VISIBLE && flips.waiting == null) card.visibility = View.GONE
    }

    private companion object {
        const val TAG = "ChannelZap"
        const val LOGO_DP = 48
        const val EDGE_DP = 24
        const val BOTTOM_DP = 32
        const val AFTER_PICTURE_MS = 1_500L
        const val LONGEST_MS = 8_000L
        const val CARD_COLOUR = 0xCC111018.toInt()
        const val NOW_COLOUR = 0xCCFFFFFF.toInt()
    }
}
