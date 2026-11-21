package com.tunedplayer.app.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.dispose
import coil3.load
import coil3.size.Precision
import com.tunedplayer.app.R
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.diagnostics.attempt

/**
 * The phone while a TV plays (Melvin, 2026-11-21: casting worked, but the phone showed the last frame of the video
 * with "Playing on SHIELD" and a spinner that never stopped, and the controls only after a tap). The phone plays
 * nothing while casting, so its screen becomes a remote for the TV:
 *
 * - The stale frame is covered ([backdrop], under Media3's controls): black, with the channel's logo or the film's
 *   poster blurred and dimmed behind.
 * - Above the centre controls ([header]): "Casting to SHIELD" with the logo, and what the TV is doing in words
 *   ("Playing", "Paused", "Loading on SHIELD…"). A spinner shows only while the TV is actually loading, or while the
 *   cast connects and starts.
 * - The controls stay up the whole time (no tap needed and no timeout): play and pause, the seek bar for films and
 *   episodes, and previous and next to change channel, all driving the TV through [ViewPlayer].
 * - Below the centre controls ([actions]): the TV's volume (Chromecast; a DLNA TV answers nothing, so it has none) and
 *   Stop casting, which ends the cast and carries on on the phone.
 *
 * Only one stream plays: the TV's. Nothing of the video is drawn on the phone.
 */
@OptIn(UnstableApi::class)
internal class CastingScreen(
    private val context: Context,
    /** The cast, if any: the volume slider and Stop casting drive it, and [follow] reads the TV from it. */
    private val cast: () -> CastControl?,
    /** How long the controls stay up when nothing is cast, to put back when the cast ends. */
    private val restTimeoutMs: () -> Int,
) {
    /** What the screen shows: the TV's name, what is on it and what it is doing. */
    data class Look(
        val tv: String,
        /** The channel's logo or the film's poster, or null. */
        val art: String?,
        /** Null until the TV is connected and has said something. */
        val state: TvState?,
        /** The TV's volume from 0 to 1, or null when it can't be changed from the phone. */
        val volume: Float?,
    )

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Int) = (value * density).toInt()

    private val art =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = ART_ALPHA
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // A soft wash of colour from the art rather than the art itself; older phones get it dimmer instead.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                attempt(TAG, "Couldn't blur the casting backdrop") {
                    setRenderEffect(RenderEffect.createBlurEffect(BLUR_PX, BLUR_PX, Shader.TileMode.CLAMP))
                }
            } else {
                alpha = ART_ALPHA / 2
            }
        }

    /** Covers the video the phone stopped on: it never shows while the TV plays. */
    val backdrop: FrameLayout =
        FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(art, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                View(context).apply {
                    background =
                        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(SHADE_TOP, SHADE_BOTTOM))
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }

    private val logo =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(LOGO_TILE)
                }
            clipToOutline = true
            val pad = dp(4)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }

    private val heading =
        TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }

    private val spinner =
        ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private val status =
        TextView(context).apply {
            setTextColor(SOFT_WHITE)
            textSize = 15f
            // Each step is read out as it happens ("Loading on SHIELD…", "Playing").
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

    /** The TV's name and what it is doing, above the centre controls. */
    val header: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            val words =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    // Both rows wrap their content: a vertical LinearLayout's default is match parent, which leaves a
                    // row out of the column's width and cut the heading to "C" (the column took "Playing"'s width).
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                ImageView(context).apply {
                                    setImageResource(R.drawable.ic_player_cast)
                                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                },
                                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) },
                            )
                            addView(heading)
                        },
                        LinearLayout.LayoutParams(WRAP, WRAP),
                    )
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(spinner, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
                            addView(status)
                        },
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) },
                    )
                }
            addView(logo, LinearLayout.LayoutParams(dp(LOGO_DP), dp(LOGO_DP)).apply { marginEnd = dp(14) })
            addView(words)
        }

    private val volume =
        SeekBar(context).apply {
            max = VOLUME_STEPS
            contentDescription = context.getString(R.string.cast_volume)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: SeekBar,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        if (fromUser) cast()?.setVolume(progress.toFloat() / VOLUME_STEPS)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) {
                        dragging = true
                    }

                    override fun onStopTrackingTouch(bar: SeekBar) {
                        dragging = false
                        cast()?.setVolume(bar.progress.toFloat() / VOLUME_STEPS)
                    }
                },
            )
        }

    private val volumeIcon =
        ImageView(context).apply {
            setImageResource(R.drawable.ic_player_volume)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    /** Stop casting: the TV stops and the phone carries on from where it was. */
    val stopButton: TextView =
        TextView(context).apply {
            text = context.getString(R.string.cast_stop)
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(TARGET_DP)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_player_close, 0, 0, 0)
            compoundDrawablePadding = dp(6)
            setPaddingRelative(dp(14), 0, dp(18), 0)
            val shape =
                GradientDrawable().apply {
                    cornerRadius = dp(TARGET_DP / 2).toFloat()
                    setColor(BUTTON_FILL)
                    setStroke(dp(1), BUTTON_EDGE)
                }
            background = RippleDrawable(ColorStateList.valueOf(BUTTON_RIPPLE), shape, null)
            isClickable = true
            isFocusable = true
            setOnClickListener { cast()?.stop() }
        }

    /** The volume and Stop casting, below the centre controls. */
    val actions: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(volumeIcon)
            addView(volume, LinearLayout.LayoutParams(dp(VOLUME_DP), dp(TARGET_DP)))
            addView(stopButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(20) })
        }

    private var playerView: PlayerView? = null
    private var look: Look? = null
    private var note: CharSequence? = null
    private var noteBusy = false
    private var dragging = false
    private var shownArt: String? = null

    /** What the header says the TV is doing, and whether its spinner shows (for tests and screenshots). */
    val statusText: CharSequence get() = status.text
    val spinning: Boolean get() = spinner.isVisible
    val volumeShown: Boolean get() = volume.isVisible

    /** Whether a TV is picked and the screen is up. */
    var shown = false
        private set

    /**
     * Puts the backdrop under Media3's controls (its overlay frame), and the header and actions on [root] above the
     * video, so the controls' own dark scrim doesn't dim them.
     */
    fun attach(
        root: FrameLayout,
        playerView: PlayerView,
    ) {
        this.playerView = playerView
        val under = playerView.overlayFrameLayout ?: root
        under.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            header,
            FrameLayout
                .LayoutParams(WRAP, WRAP, Gravity.CENTER)
                .apply {
                    bottomMargin = dp(HEADER_RAISE_DP)
                    marginStart = dp(SIDE_DP)
                    marginEnd = dp(SIDE_DP)
                },
        )
        root.addView(
            actions,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { topMargin = dp(ACTIONS_DROP_DP) },
        )
        render()
    }

    /** The slider in the app's accent, like the seek bar. */
    fun tint(accent: Int) {
        volume.progressTintList = ColorStateList.valueOf(accent)
        volume.thumbTintList = ColorStateList.valueOf(accent)
    }

    /** A cast began, changed or ended, or [item] took over on the TV: shows what [cast] says. */
    fun follow(item: ItemEntity?) {
        val now = cast()
        val tv = now?.tvName
        update(if (now == null || tv == null) null else Look(tv, item?.logo, if (now.casting) now.tv.state() else null, now.volume))
    }

    /** How long the controls stay up: for good while casting, else as set. */
    fun timeoutMs(): Int = if (shown) 0 else restTimeoutMs()

    /** The player's notes go here from the moment a TV is picked, else to [elsewhere]. */
    fun notesOr(elsewhere: (String?, Boolean) -> Unit): (String?, Boolean) -> Unit =
        { text, busy -> if (cast()?.tvName != null) note(text, busy) else elsewhere(text, busy) }

    /** A cast began, changed or ended ([look] null). */
    fun update(look: Look?) {
        this.look = look
        if (look == null) {
            note = null
            noteBusy = false
        }
        render()
    }

    /**
     * A step on the way, or a problem, in words ("Connecting to SHIELD…", "SHIELD couldn't play this"), shown in place
     * of what the TV is doing until cleared with null. [busy] steps show the spinner.
     */
    fun note(
        text: CharSequence?,
        busy: Boolean,
    ) {
        note = text
        noteBusy = text != null && busy
        render()
    }

    private fun render() {
        val now = look
        val on = now != null
        controls(on)
        val visible = if (on) View.VISIBLE else View.GONE
        backdrop.visibility = visible
        header.visibility = visible
        actions.visibility = visible
        if (now == null) {
            showArt(null)
            return
        }
        heading.text = context.getString(R.string.cast_casting_to, now.tv)
        showArt(now.art)
        val line = now.state?.let(TvLine::of)
        val (words, busy) =
            when {
                note != null -> requireNotNull(note) to noteBusy
                line == null -> context.getString(R.string.cast_starting, now.tv) to true
                else -> context.getString(line.words, now.tv) to (line == TvLine.LOADING)
            }
        status.text = words
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        val canVolume = now.volume != null
        volume.visibility = if (canVolume) View.VISIBLE else View.GONE
        volumeIcon.visibility = volume.visibility
        if (canVolume && !dragging) volume.progress = (requireNotNull(now.volume) * VOLUME_STEPS).toInt().coerceIn(0, VOLUME_STEPS)
    }

    /**
     * While casting the controls stay up with no timeout and a tap doesn't hide them, and Media3's own spinner is off
     * (the stopped phone player isn't loading anything; the header says when the TV is).
     */
    private fun controls(on: Boolean) {
        val view = playerView ?: return
        if (on == shown) return
        shown = on
        view.setShowBuffering(if (on) PlayerView.SHOW_BUFFERING_NEVER else PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        view.controllerHideOnTouch = !on
        view.setControllerShowTimeoutMs(if (on) 0 else restTimeoutMs())
        if (on) view.showController()
    }

    private fun showArt(url: String?) {
        if (url == shownArt) return
        shownArt = url
        art.dispose()
        logo.dispose()
        art.setImageDrawable(null)
        logo.setImageDrawable(null)
        logo.visibility = if (url.isNullOrBlank()) View.GONE else View.VISIBLE
        if (url.isNullOrBlank()) return
        art.load(url) {
            size(ART_PX, ART_PX)
            precision(Precision.INEXACT)
        }
        val px = dp(LOGO_DP)
        logo.load(url) {
            size(px, px)
            precision(Precision.INEXACT)
        }
    }

    private companion object {
        const val TAG = "Cast"
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val VOLUME_STEPS = 20
        const val LOGO_DP = 56
        const val TARGET_DP = 48
        const val VOLUME_DP = 180
        const val SIDE_DP = 24

        /** The header's centre sits this far above the screen's, clear of the title above and play below. */
        const val HEADER_RAISE_DP = 100

        /** The actions' centre sits this far below the screen's, clear of play above and the seek bar below. */
        const val ACTIONS_DROP_DP = 88
        const val ART_ALPHA = 0.5f
        const val ART_PX = 256
        const val BLUR_PX = 60f
        const val SHADE_TOP = 0x99000000.toInt()
        const val SHADE_BOTTOM = 0xE6000000.toInt()
        const val LOGO_TILE = 0x33FFFFFF
        const val SOFT_WHITE = 0xD9FFFFFF.toInt()
        const val BUTTON_FILL = 0x26FFFFFF
        const val BUTTON_EDGE = 0x66FFFFFF
        const val BUTTON_RIPPLE = 0x40FFFFFF
    }
}

/** What the TV is doing, in the casting screen's words. */
internal enum class TvLine(
    @StringRes val words: Int,
) {
    LOADING(R.string.cast_tv_loading),
    PLAYING(R.string.cast_tv_playing),
    PAUSED(R.string.cast_tv_paused),
    FINISHED(R.string.cast_tv_finished),
    STOPPED(R.string.cast_tv_stopped),
    ;

    companion object {
        fun of(state: TvState): TvLine = when (state.playbackState) {
            Player.STATE_BUFFERING -> LOADING
            Player.STATE_READY -> if (state.playWhenReady) PLAYING else PAUSED
            Player.STATE_ENDED -> FINISHED
            else -> STOPPED
        }
    }
}
