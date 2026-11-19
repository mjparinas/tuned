package app.tuned.player

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.tuned.R
import app.tuned.core.RemoteKeys
import app.tuned.data.VideoFit
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.launchSafely
import app.tuned.diagnostics.startActivitySafely
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The buttons at the top right of the player, shown with the controls: channels (live), aspect, quality, stream info and
 * screen lock. The subtitle timing button sits beside the CC button instead ([placeBesideCaptions]). Each stays out of
 * the way until asked for, as everything in the player does.
 */
@OptIn(UnstableApi::class)
class PlayerTools(
    private val activity: ComponentActivity,
    private val playerView: PlayerView,
    private val overlay: View,
    private val hooks: Hooks,
    /** The channel's copies in other picture qualities, for the HQ button. */
    private val copies: QualityCopies,
) {
    /** What the tools need from the player screen, read when used so they always see the current player. */
    class Hooks(
        val player: () -> ExoPlayer?,
        val queue: () -> List<ItemEntity>,
        val isLive: () -> Boolean,
        val touch: () -> View.OnTouchListener?,
        val say: (String) -> Unit,
        val onFitChanged: (VideoFit) -> Unit,
        val subtitles: SubtitleTiming,
        val casting: () -> Boolean,
        /** Typing a channel number and flipping back, on live TV. */
        val dial: ChannelDial,
    )

    private val density = activity.resources.displayMetrics.density
    private val guide = MiniGuide(activity, hooks.player, hooks.queue, beside = listOf(playerView, overlay), onClosed = { syncBack() })
    private val guideButton = button(R.drawable.ic_player_guide, R.string.player_guide) { toggleGuide() }
    private val flipButton = button(R.drawable.ic_player_flip_back, R.string.player_flip_back) { hooks.dial.flipBack() }
    private val qualityButton =
        button(R.drawable.ic_player_quality, R.string.player_quality) { pickQuality() }.apply { visibility = View.GONE }
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
            addView(flipButton)
            addView(qualityButton)
            addView(button(R.drawable.ic_player_more, R.string.player_more) { view -> showMore(view) })
        }

    /** Moves the captions earlier or later; placed beside the CC button by the player. */
    val subtitleTimingButton: ImageButton = button(R.drawable.ic_player_subtitle_timing, R.string.subtitle_timing) { pickSubtitleTiming() }

    /**
     * The tools used now and then sit behind one More button, so the top row keeps to what's used while watching
     * (cast, record, channels, flip back, quality): up to nine buttons were in that row (2026-11-18, Melvin's UI review).
     */
    private fun showMore(anchor: View) {
        val items =
            listOf(
                R.string.player_fit to ::nextFit,
                R.string.player_info to ::toggleInfo,
                R.string.player_open_with to ::openElsewhere,
                R.string.player_lock to ::lock,
            )
        // While casting the TV draws the picture and the phone has no stream: only the lock still applies.
        val casting = hooks.casting()
        PopupMenu(activity, anchor).apply {
            items.forEachIndexed { index, (label, _) ->
                menu.add(Menu.NONE, index, index, label).isEnabled = !casting || label == R.string.player_lock
            }
            setOnMenuItemClickListener { item ->
                items.getOrNull(item.itemId)?.second?.invoke()
                true
            }
            show()
        }
    }

    /** Puts another button (the cast button) at the start of the toolbar. */
    fun addButton(view: View) {
        toolbar.addView(view, 0)
    }

    /** Adds the panels (guide, info, unlock) over the video. */
    fun attach(root: FrameLayout) {
        root.addView(
            guide.scrim,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.addView(
            guide.view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END),
        )
        guide.place()
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
            refreshQuality()
            guideButton.visibility = if (hooks.isLive() && hooks.queue().size > 1) View.VISIBLE else View.GONE
            flipButton.visibility = if (hooks.isLive() && hooks.dial.canFlipBack) View.VISIBLE else View.GONE
            // The TV draws the captions, so their timing can't be moved there.
            subtitleTimingButton.usable(!hooks.casting())
        }
    }

    private var tracks: Tracks = Tracks.EMPTY

    /**
     * The quality (HQ) button shows only when there is something to switch to (Melvin, 2026-11-18: "otherwise the hq
     * button is useless"): another copy of the channel in a different picture quality ([QualityCopies]), or a stream
     * with several renditions of its own (an HLS stream). Most IPTV channels are one TS rendition with no copies.
     */
    fun onTracks(tracks: Tracks) {
        this.tracks = tracks
        refreshQuality()
    }

    /** The copies or the stream changed, or the controls showed. */
    fun refreshQuality() {
        qualityButton.isVisible = renditionChoice(tracks) || copies.exist
        qualityButton.usable(!hooks.casting())
    }

    private fun renditionChoice(tracks: Tracks): Boolean =
        StreamInfo.hasQualityChoice(qualityOptions(tracks).map { it.height to it.bitrate })

    private class QualityOption(
        val group: Tracks.Group,
        val index: Int,
        val height: Int,
        val bitrate: Int,
    )

    private fun qualityOptions(tracks: Tracks): List<QualityOption> = tracks.groups
        .filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { group ->
            (0 until group.length).filter(group::isTrackSupported).map { i ->
                val f = group.getTrackFormat(i)
                QualityOption(group, i, f.height, f.bitrateOrAverage())
            }
        }.sortedByDescending { it.height }

    fun applyFit(value: VideoFit) {
        fit = value
        playerView.resizeMode =
            when (value) {
                VideoFit.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                VideoFit.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                VideoFit.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
    }

    /** Rotation, folding or split screen: the guide moves beside or over the video to suit. */
    fun onScreenChanged() = guide.place()

    /** Picture-in-picture: nothing but the video. */
    fun hidePanels() {
        guide.close()
        info.visibility = View.GONE
        infoJob?.cancel()
        unlockButton.visibility = View.GONE
        syncBack()
    }

    /**
     * TV remotes ([RemoteKeys]): channel up and down, the guide and info buttons, and the arrows while the controls are
     * hidden. True when the key was used here; both the press and the release are taken, so Media3 doesn't also show
     * its controls for it. Nothing changes while the screen is locked, and while the guide is open only the guide
     * and menu buttons (which close it) are taken; the arrows move through its list.
     */
    fun onKey(event: KeyEvent): Boolean {
        val guideKey = event.keyCode == RemoteKeys.GUIDE || event.keyCode == RemoteKeys.MENU
        if (locked || (guide.isOpen && !guideKey)) return false
        val digit = RemoteKeys.digit(event.keyCode)
        if (digit != null && hooks.isLive()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) hooks.dial.type(digit)
            return true
        }
        val action = RemoteKeys.action(event.keyCode, hooks.isLive(), playerView.isControllerFullyVisible)
        if (action == RemoteKeys.Action.NONE) return false
        val seek = action == RemoteKeys.Action.SEEK_FORWARD || action == RemoteKeys.Action.SEEK_BACK
        // Holding an arrow keeps skipping; holding a channel button changes channel once.
        if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || seek)) perform(action)
        return true
    }

    private fun perform(action: RemoteKeys.Action) {
        val player = hooks.player()
        when (action) {
            RemoteKeys.Action.NEXT_CHANNEL -> {
                player?.seekToNextMediaItem()
            }

            RemoteKeys.Action.PREVIOUS_CHANNEL -> {
                player?.seekToPreviousMediaItem()
            }

            RemoteKeys.Action.SEEK_FORWARD -> {
                player?.let {
                    it.seekForward()
                    hooks.say(activity.getString(R.string.gesture_forward, (it.seekForwardIncrement / MS_PER_S).toInt()))
                }
            }

            RemoteKeys.Action.SEEK_BACK -> {
                player?.let {
                    it.seekBack()
                    hooks.say(activity.getString(R.string.gesture_back, (it.seekBackIncrement / MS_PER_S).toInt()))
                }
            }

            RemoteKeys.Action.GUIDE -> {
                if (hooks.queue().size > 1) toggleGuide()
            }

            RemoteKeys.Action.INFO -> {
                toggleInfo()
            }

            RemoteKeys.Action.LAST_CHANNEL -> {
                hooks.dial.flipBack()
            }

            RemoteKeys.Action.NONE -> {}
        }
    }

    /**
     * Hands the stream to another installed player (VLC, MX Player, Just Player) for a format Tuned can't play
     * (from the app audit). Tuned stops its own stream first and closes, so the account never has two open.
     */
    private fun openElsewhere() {
        val player = hooks.player() ?: return
        val item = hooks.queue().getOrNull(player.currentMediaItemIndex) ?: return
        val view =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(item.url.toUri(), "video/*")
                .putExtra(Intent.EXTRA_TITLE, item.name)
                .putExtra("title", item.name) // what VLC and MX Player read
        player.stop()
        val chooser = Intent.createChooser(view, activity.getString(R.string.player_open_with))
        if (activity.startActivitySafely(chooser, "PlayerTools")) {
            activity.finish()
        } else {
            hooks.say(activity.getString(R.string.player_open_with_none))
            player.prepare()
        }
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
            activity.lifecycleScope.launchSafely("PlayerTools") {
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
            activity.lifecycleScope.launchSafely("PlayerTools") {
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

    private fun pickSubtitleTiming() {
        val p = hooks.player() ?: return
        if (hooks.casting()) {
            hooks.say(activity.getString(R.string.subtitle_timing_not_casting))
        } else {
            hooks.subtitles.pick(activity, p, hooks.say)
        }
    }

    private fun pickQuality() {
        val p = hooks.player() ?: return
        val options = qualityOptions(p.currentTracks)
        if (!StreamInfo.hasQualityChoice(options.map { it.height to it.bitrate })) {
            // One rendition: the choice is between the channel's copies, if it has any.
            if (copies.available) copies.pick(activity) else hooks.say(activity.getString(R.string.player_quality_only_one))
            return
        }
        val chosen =
            options.indexOfFirst { o ->
                p.trackSelectionParameters.overrides[o.group.mediaTrackGroup]?.trackIndices == listOf(o.index)
            }
        val labels = listOf(activity.getString(R.string.player_quality_auto)) + options.map { StreamInfo.quality(it.height, it.bitrate) }
        AlertDialog
            .Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.player_quality)
            .setSingleChoiceItems(labels.toTypedArray(), chosen + 1) { dialog, which ->
                val builder = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                if (which > 0) {
                    val o = options[which - 1]
                    builder.addOverride(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                }
                p.trackSelectionParameters = builder.build()
                dialog.dismiss()
            }.show()
    }

    private fun button(
        icon: Int,
        label: Int,
        onClick: (View) -> Unit,
    ) = ImageButton(activity).apply {
        setImageResource(icon)
        contentDescription = activity.getString(label)
        val ripple = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        setBackgroundResource(ripple.resourceId)
        val pad = (12 * density).toInt()
        setPadding(pad, pad, pad, pad)
        setOnClickListener { onClick(it) }
    }

    private companion object {
        const val UNLOCK_SHOWN_MS = 3_000L
        const val MS_PER_S = 1_000L
    }
}
