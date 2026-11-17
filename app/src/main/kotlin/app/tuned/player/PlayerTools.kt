package app.tuned.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.tuned.R
import app.tuned.data.VideoFit
import app.tuned.data.db.ItemEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The buttons at the top right of the player, shown with the controls: channels (live), aspect, quality, stream info
 * and screen lock. Each stays out of the way until asked for, as everything in the player does.
 */
@OptIn(UnstableApi::class)
class PlayerTools(
    private val activity: ComponentActivity,
    private val playerView: PlayerView,
    private val overlay: View,
    private val hooks: Hooks,
) {
    /** What the tools need from the player screen, read when used so they always see the current player. */
    class Hooks(
        val player: () -> ExoPlayer?,
        val queue: () -> List<ItemEntity>,
        val isLive: () -> Boolean,
        val touch: () -> View.OnTouchListener?,
        val say: (String) -> Unit,
        val onFitChanged: (VideoFit) -> Unit,
    )

    private val density = activity.resources.displayMetrics.density
    private val guide = MiniGuide(activity, hooks.player, hooks.queue)
    private val guideButton = button(R.drawable.ic_player_guide, R.string.player_guide) { toggleGuide() }
    private val unlockButton =
        button(R.drawable.ic_player_lock, R.string.player_unlock) { unlock() }.apply {
            visibility = View.GONE
            setBackgroundColor(0x99000000.toInt())
        }
    private val info =
        TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(0xB3000000.toInt())
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
    private var infoJob: Job? = null
    private var unlockJob: Job? = null
    private var fit = VideoFit.FIT

    var locked = false
        private set

    /** Back closes the guide first, and does nothing while the screen is locked. */
    private val back =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    locked -> showUnlock()
                    guide.isOpen -> guide.close()
                }
                syncBack()
            }
        }

    val toolbar: LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(guideButton)
            addView(button(R.drawable.ic_player_aspect, R.string.player_fit) { nextFit() })
            addView(button(R.drawable.ic_player_quality, R.string.player_quality) { pickQuality() })
            addView(button(R.drawable.ic_player_info, R.string.player_info) { toggleInfo() })
            addView(button(R.drawable.ic_player_lock, R.string.player_lock) { lock() })
        }

    /** Adds the panels (guide, info, unlock) over the video. */
    fun attach(root: FrameLayout) {
        root.addView(
            guide.view,
            FrameLayout.LayoutParams((320 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END),
        )
        root.addView(
            info,
            FrameLayout
                .LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                .apply { setMargins((24 * density).toInt(), 0, 0, (96 * density).toInt()) },
        )
        root.addView(
            unlockButton,
            FrameLayout
                .LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_VERTICAL or Gravity.START,
                ).apply { marginStart = (24 * density).toInt() },
        )
        activity.onBackPressedDispatcher.addCallback(activity, back)
    }

    /** Called as the controls show or hide: the channels button only makes sense with channels to flip through. */
    fun onControls(visibility: Int) {
        if (visibility == View.VISIBLE) {
            guideButton.visibility = if (hooks.isLive() && hooks.queue().size > 1) View.VISIBLE else View.GONE
        }
    }

    fun applyFit(value: VideoFit) {
        fit = value
        playerView.resizeMode =
            when (value) {
                VideoFit.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                VideoFit.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                VideoFit.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
    }

    /** Picture-in-picture: nothing but the video. */
    fun hidePanels() {
        guide.close()
        info.visibility = View.GONE
        infoJob?.cancel()
        unlockButton.visibility = View.GONE
        syncBack()
    }

    fun release() {
        infoJob?.cancel()
        unlockJob?.cancel()
    }

    private fun nextFit() {
        val next = VideoFit.entries[(fit.ordinal + 1) % VideoFit.entries.size]
        applyFit(next)
        hooks.onFitChanged(next)
        hooks.say(
            activity.getString(
                when (next) {
                    VideoFit.FIT -> R.string.player_fit_fit
                    VideoFit.ZOOM -> R.string.player_fit_zoom
                    VideoFit.STRETCH -> R.string.player_fit_stretch
                },
            ),
        )
    }

    private fun toggleGuide() {
        if (guide.isOpen) guide.close() else guide.open(activity.lifecycleScope)
        if (guide.isOpen) playerView.hideController()
        syncBack()
    }

    // ---- Screen lock: the video keeps playing, every touch is ignored except the lock button that a tap reveals.

    @SuppressLint("ClickableViewAccessibility") // performClick is still called; with the controller off it does nothing
    private fun lock() {
        locked = true
        playerView.hideController()
        playerView.useController = false
        overlay.visibility = View.GONE
        hidePanels()
        playerView.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                v.performClick()
                showUnlock()
            }
            true
        }
        hooks.say(activity.getString(R.string.player_locked))
        syncBack()
    }

    private fun showUnlock() {
        unlockButton.visibility = View.VISIBLE
        unlockButton.requestFocus()
        unlockJob?.cancel()
        unlockJob =
            activity.lifecycleScope.launch {
                delay(UNLOCK_SHOWN_MS)
                unlockButton.visibility = View.GONE
            }
    }

    private fun unlock() {
        locked = false
        unlockJob?.cancel()
        unlockButton.visibility = View.GONE
        playerView.setOnTouchListener(hooks.touch())
        playerView.useController = true
        playerView.showController()
        syncBack()
    }

    private fun syncBack() {
        back.isEnabled = locked || guide.isOpen
    }

    // ---- Stream info: what is actually playing, refreshed every second while open.

    private fun toggleInfo() {
        if (info.isVisible) {
            info.visibility = View.GONE
            infoJob?.cancel()
            return
        }
        info.visibility = View.VISIBLE
        infoJob =
            activity.lifecycleScope.launch {
                while (isActive) {
                    info.text = infoText()
                    delay(1_000)
                }
            }
    }

    private fun infoText(): String {
        val p = hooks.player() ?: return activity.getString(R.string.player_info_none)
        val video = p.videoFormat
        val audio = p.audioFormat
        val lines =
            listOfNotNull(
                video?.let {
                    val size = p.videoSize
                    val text = StreamInfo.video(size.width, size.height, it.frameRate, it.sampleMimeType, it.bitrateOrAverage())
                    activity.getString(R.string.player_info_video, text)
                },
                audio?.let {
                    val channels =
                        it.channelCount.takeIf { n -> n > 0 }?.let { n ->
                            activity.resources.getQuantityString(R.plurals.player_audio_channels, n, n)
                        }
                    activity.getString(
                        R.string.player_info_audio,
                        StreamInfo.audio(it.sampleMimeType, channels, it.sampleRate, it.language),
                    )
                },
                activity.getString(R.string.player_info_buffer, (p.totalBufferedDuration / 1_000f)),
            )
        return if (video == null && audio == null) activity.getString(R.string.player_info_none) else lines.joinToString("\n")
    }

    private fun Format.bitrateOrAverage(): Int = if (bitrate != Format.NO_VALUE) bitrate else averageBitrate

    // ---- Quality: the stream's own renditions (HLS variants); most single-rendition TS channels have just one.

    private fun pickQuality() {
        val p = hooks.player() ?: return
        val options =
            p.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_VIDEO }
                .flatMap { group ->
                    (0 until group.length).filter(group::isTrackSupported).map { i -> group to i }
                }.sortedByDescending { (group, i) -> group.getTrackFormat(i).height }
        if (options.size <= 1) {
            hooks.say(activity.getString(R.string.player_quality_only_one))
            return
        }
        val chosen =
            options.indexOfFirst { (group, i) ->
                p.trackSelectionParameters.overrides[group.mediaTrackGroup]?.trackIndices == listOf(i)
            }
        val labels =
            listOf(activity.getString(R.string.player_quality_auto)) +
                options.map { (group, i) ->
                    val f = group.getTrackFormat(i)
                    StreamInfo.quality(f.height, f.bitrateOrAverage())
                }
        AlertDialog
            .Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.player_quality)
            .setSingleChoiceItems(labels.toTypedArray(), chosen + 1) { dialog, which ->
                val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                if (which > 0) {
                    val (group, i) = options[which - 1]
                    builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, i))
                }
                p.trackSelectionParameters = builder.build()
                dialog.dismiss()
            }.show()
    }

    private fun button(
        icon: Int,
        label: Int,
        onClick: () -> Unit,
    ) = ImageButton(activity).apply {
        setImageResource(icon)
        contentDescription = activity.getString(label)
        val ripple = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        setBackgroundResource(ripple.resourceId)
        val pad = (12 * density).toInt()
        setPadding(pad, pad, pad, pad)
        setOnClickListener { onClick() }
    }

    private companion object {
        const val UNLOCK_SHOWN_MS = 3_000L
    }
}
