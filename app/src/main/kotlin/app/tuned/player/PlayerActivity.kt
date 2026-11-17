package app.tuned.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import app.tuned.BuildConfig
import app.tuned.R
import app.tuned.container
import app.tuned.core.ContentKind
import app.tuned.data.AppSettings
import app.tuned.data.db.HistoryEntity
import app.tuned.data.db.ItemEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Full-screen playback. Nothing on screen but the video until a tap shows the standard Media3 controls: play and
 * pause, seek, previous and next (the next channel in the category, or the next episode), captions, and the
 * settings menu for audio language and speed. Built on Views, not Compose, because PlayerView is Media3's own
 * tested surface and starts fastest.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlay: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private var settings = AppSettings()
    private var queue: List<ItemEntity> = emptyList()
    private var progressJob: Job? = null
    private var liveRetries = 0
    private var autoTune: AutoTune? = null
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildViews()
        hideSystemBars()
        lifecycleScope.launch {
            settings = container.settings.settings.first()
            if (settings.landscapePlayback) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val query = intent.getStringExtra(EXTRA_TUNE)
            if (query != null) {
                startTune(query)
                return@launch
            }
            val id = intent.getLongExtra(EXTRA_ID, 0)
            val item = container.db.items().get(id)
            if (item == null) {
                finish()
                return@launch
            }
            queue = siblings(item)
            start(item)
        }
    }

    private fun buildViews() {
        playerView =
            PlayerView(this).apply {
                setBackgroundColor(Color.BLACK)
                keepScreenOn = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setShowSubtitleButton(true)
            }
        titleView =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        subtitleView =
            TextView(this).apply {
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 14f
            }
        val density = resources.displayMetrics.density
        // Back sits with the title, so it shows whenever the controls do (system back and gestures work too).
        val back =
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_arrow_back)
                contentDescription = getString(R.string.back)
                val ripple = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                setBackgroundResource(ripple.resourceId)
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
                setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            }
        val titles =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(subtitleView)
            }
        overlay =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.player_top_scrim)
                val pad = (24 * density).toInt()
                setPadding(pad / 2, pad / 2, pad, pad * 2)
                addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(
                    titles,
                    LinearLayout
                        .LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = (4 * density).toInt() },
                )
                gravity = Gravity.CENTER_VERTICAL
            }
        statusView =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                visibility = View.GONE
                setBackgroundColor(0x99000000.toInt())
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
            }
        val root = FrameLayout(this)
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )
        root.addView(
            statusView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        // The title shows and hides with the controls, so a tap reveals everything and the video is otherwise bare.
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                overlay.visibility = visibility
            },
        )
        setContentView(root)
    }

    /** Live: the other channels in the same category, so next/previous flips channels. Shows: the show's episodes. */
    private suspend fun siblings(item: ItemEntity): List<ItemEntity> {
        val items = container.db.items()
        return when (ContentKind.fromCode(item.kind)) {
            ContentKind.LIVE -> items.channelsInGroup(item.groupName, settings.hiddenRegions.toList(), settings.showAdult)
            ContentKind.SERIES -> item.showKey?.let { items.episodesNow(it) }.orEmpty()
            ContentKind.MOVIE -> emptyList()
        }.ifEmpty { listOf(item) }
    }

    /** Auto-tune: queue the channels for an event, best first, and let [AutoTune] find one that plays. */
    private suspend fun startTune(query: String) {
        val ids = container.tune.candidates(query)
        val byId =
            container.db
                .items()
                .getAll(ids)
                .associateBy { it.id }
        queue = ids.mapNotNull(byId::get)
        if (queue.isEmpty()) {
            Toast.makeText(this, getString(R.string.tune_nothing_found, query), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val tuner =
            AutoTune(
                scope = lifecycleScope,
                tune = container.tune,
                timeoutMs = settings.tuneTimeoutMs.toLong(),
                minHeight = settings.tuneMinHeight,
                onStatus = { position ->
                    statusView.visibility = if (position == null) View.GONE else View.VISIBLE
                    if (position != null) statusView.text = getString(R.string.tune_trying, position, queue.size)
                },
                onGiveUp = { Toast.makeText(this, getString(R.string.tune_none, query), Toast.LENGTH_LONG).show() },
            )
        autoTune = tuner
        start(queue.first())
        val exo = requireNotNull(player)
        tuner.player = exo
        exo.addListener(tuner)
        tuner.begin()
    }

    private suspend fun start(item: ItemEntity) {
        val kind = ContentKind.fromCode(item.kind)
        val live = kind == ContentKind.LIVE
        val exo = buildPlayer(live)
        player = exo
        playerView.player = exo
        playerView.setControllerShowTimeoutMs(settings.controlsTimeoutMs)
        playerView.setShowNextButton(queue.size > 1)
        playerView.setShowPreviousButton(queue.size > 1)
        playerView.setShowFastForwardButton(!live)
        playerView.setShowRewindButton(!live)
        CaptionStyles.apply(requireNotNull(playerView.subtitleView), settings.captions)

        val mediaItems = queue.map { it.toMediaItem() }
        val index = queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        val resumeAt = if (!live && settings.resumePlayback) resumePosition(item.id) else C.TIME_UNSET
        exo.setMediaItems(mediaItems, index, resumeAt)
        exo.pauseAtEndOfMediaItems = kind == ContentKind.SERIES && !settings.autoPlayNext
        exo.prepare()
        exo.playWhenReady = true
        updateOverlay(queue[index])
        if (!live) progressJob = lifecycleScope.launch { saveProgressEvery(exo) }
    }

    private fun buildPlayer(live: Boolean): ExoPlayer {
        val http =
            OkHttpDataSource
                .Factory(container.http)
                .setUserAgent(settings.userAgent.ifBlank { "Tuned/${BuildConfig.VERSION_NAME}" })
        // TS streams from IPTV servers often start on a non-IDR frame; these flags start them sooner.
        val extractors =
            DefaultExtractorsFactory().setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
        val buffer = settings.buffer
        val load =
            DefaultLoadControl
                .Builder()
                .setBufferDurationsMs(buffer.minMs, buffer.maxMs, buffer.startMs, buffer.startMs * 2)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        val captions = settings.captions
        val selector =
            DefaultTrackSelector(this).apply {
                setParameters(
                    buildUponParameters()
                        .setPreferredAudioLanguage(settings.audioLanguage.ifBlank { null })
                        .setPreferredTextLanguage(captions.language.ifBlank { null })
                        .setSelectUndeterminedTextLanguage(true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captions.enabled),
                )
            }
        return ExoPlayer
            .Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
            .setLoadControl(load)
            .setTrackSelector(selector)
            .setSeekBackIncrementMs(settings.seekBackMs)
            .setSeekForwardIncrementMs(settings.seekForwardMs)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            ).setHandleAudioBecomingNoisy(true)
            .build()
            .apply { addListener(listener(live)) }
    }

    private fun listener(live: Boolean) = object : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int,
        ) {
            val id = mediaItem?.mediaId?.toLongOrNull() ?: return
            queue.firstOrNull { it.id == id }?.let(::updateOverlay)
            liveRetries = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            // Auto-tune: a channel that fails while being tried is dead; move straight on to the next one.
            val tuner = autoTune
            if (tuner != null && tuner.searching) {
                tuner.failCurrent()
                return
            }
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            // Live streams drop now and then; try again quietly a few times before saying anything.
            if (live && liveRetries < MAX_LIVE_RETRIES) {
                liveRetries++
                lifecycleScope.launch {
                    delay(1_500L * liveRetries)
                    p.prepare()
                }
                return
            }
            // A tuned channel that dropped for good: carry on to the next working one, as a radio would.
            if (tuner != null) {
                tuner.failCurrent()
                return
            }
            Toast.makeText(this@PlayerActivity, getString(R.string.player_error, error.errorCodeName), Toast.LENGTH_LONG).show()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setPictureInPictureParams(pipParams())
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setPictureInPictureParams(pipParams())
        }
    }

    private fun updateOverlay(item: ItemEntity) {
        titleView.text = item.name
        subtitleView.text = item.groupName
        if (ContentKind.fromCode(item.kind) == ContentKind.LIVE) rememberChannel(item.id)
        val tvgId = item.tvgId ?: return
        if (ContentKind.fromCode(item.kind) != ContentKind.LIVE) return
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val shows = container.db.programmes().nowAndNext(tvgId, now)
            val current = shows.firstOrNull { it.start <= now }
            val next = shows.firstOrNull { it.start > now }
            subtitleView.text =
                listOfNotNull(
                    current?.let { getString(R.string.player_now, it.title) },
                    next?.let { getString(R.string.player_next, it.title) },
                ).joinToString("  ·  ").ifBlank { item.groupName }
        }
    }

    private suspend fun resumePosition(id: Long): Long {
        val h = container.db.library().progress(id) ?: return C.TIME_UNSET
        val nearlyDone = h.durationMs > 0 && h.positionMs > h.durationMs * 0.95
        return if (h.positionMs > 30_000 && !nearlyDone) h.positionMs else C.TIME_UNSET
    }

    /** Live channels go into history too (no position), for the Recent filter on Live TV. */
    private fun rememberChannel(id: Long) {
        container.appScope.launch {
            container.db.library().saveProgress(HistoryEntity(id, 0, 0, System.currentTimeMillis()))
        }
    }

    private suspend fun saveProgressEvery(exo: ExoPlayer) {
        while (true) {
            delay(10_000)
            saveProgress(exo)
        }
    }

    private suspend fun saveProgress(exo: ExoPlayer) {
        val id = exo.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val duration = exo.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        container.db.library().saveProgress(HistoryEntity(id, exo.currentPosition, duration, System.currentTimeMillis()))
    }

    private fun live(): Boolean = queue.firstOrNull()?.kind == ContentKind.LIVE.code

    private fun ItemEntity.toMediaItem(): MediaItem = MediaItem
        .Builder()
        .setMediaId(id.toString())
        .setUri(url)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
        .build()

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val size = player?.videoSize
        val ratio =
            if (size != null && size.width > 0 && size.height > 0) {
                Rational(size.width, size.height).coerceForPip()
            } else {
                Rational(16, 9)
            }
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        // Where the video is on screen, so the shrink into the window animates from the picture, not the whole screen.
        val bounds = Rect()
        if (playerView.videoSurfaceView?.getGlobalVisibleRect(bounds) == true) builder.setSourceRectHint(bounds)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(settings.pictureInPicture && player?.isPlaying == true)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** Android refuses ratios outside 1:2.39 to 2.39:1. */
    private fun Rational.coerceForPip(): Rational {
        val value = toFloat()
        return when {
            value > 2.39f -> Rational(239, 100)
            value < 1 / 2.39f -> Rational(100, 239)
            else -> this
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters picture-in-picture by itself (setAutoEnterEnabled); older versions need asking.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && settings.pictureInPicture && player?.isPlaying == true) {
            enterPictureInPictureMode(pipParams())
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            playerView.hideController()
            overlay.visibility = View.GONE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        // Outlives the activity, so the last position is saved even as it closes.
        player?.let { exo ->
            val id = exo.currentMediaItem?.mediaId?.toLongOrNull()
            val position = exo.currentPosition
            val duration = exo.duration
            if (id != null && duration > 0 && !live()) {
                container.appScope.launch {
                    container.db.library().saveProgress(HistoryEntity(id, position, duration, System.currentTimeMillis()))
                }
            }
        }
        // Leaving the app, or closing the picture-in-picture window, stops the activity: playback ends there.
        // (In picture-in-picture the activity is only paused, so it keeps playing.)
        if (!isChangingConfigurations) {
            player?.pause()
            finish()
        }
    }

    override fun onDestroy() {
        progressJob?.cancel()
        autoTune?.release()
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TUNE = "tune"
        private const val MAX_LIVE_RETRIES = 3

        fun start(
            context: Context,
            itemId: Long,
        ) {
            context.startActivity(Intent(context, PlayerActivity::class.java).putExtra(EXTRA_ID, itemId))
        }

        /** Auto-tune: find a working channel showing [query] (a channel name or an event in the guide) and play it. */
        fun tune(
            context: Context,
            query: String,
        ) {
            context.startActivity(Intent(context, PlayerActivity::class.java).putExtra(EXTRA_TUNE, query))
        }
    }
}
