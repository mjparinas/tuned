package app.tuned.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
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
import app.tuned.core.StreamLink
import app.tuned.data.AppSettings
import app.tuned.data.db.HistoryEntity
import app.tuned.data.db.ItemEntity
import app.tuned.data.hiddenKeys
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.launchSafely
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Full-screen playback. Nothing on screen but the video until a tap shows the standard Media3 controls: play and
 * pause, seek, previous and next (the next channel in the category, or the next episode), captions, and the
 * settings menu for audio language and speed. Built on Views, not Compose, because PlayerView is Media3's own
 * tested surface and starts fastest.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : FragmentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlay: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private var settings = AppSettings()
    private var queue: List<ItemEntity> = emptyList()

    /** Headers the playlist asks for, by stream host; read by the player's network requests on their own threads. */
    private val streamHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private var progressJob: Job? = null
    private var liveRetries = 0
    private var gestures: PlayerGestures? = null
    private val skipIntro by lazy {
        SkipIntro(
            activity = this,
            player = { player?.takeIf { cast?.casting != true } },
            current = {
                player
                    ?.currentMediaItem
                    ?.mediaId
                    ?.toLongOrNull()
                    ?.let { id -> queue.firstOrNull { it.id == id } }
            },
            lengthMs = { settings.skipIntroMs },
        )
    }
    private val levelBars by lazy { LevelBars(this) }
    private lateinit var tools: PlayerTools
    private var cast: CastControl? = null
    private val subtitleTiming = SubtitleTiming()
    private var reconnect: ConnectivityManager.NetworkCallback? = null
    private var autoTune: AutoTune? = null
    private val tuneNext by lazy {
        TuneNext(
            activity = this,
            hasNext = { autoTune != null && player?.hasNextMediaItem() == true },
            onNext = { player?.seekToNextMediaItem() },
        )
    }

    /** The current channel has drawn a picture since it started (a channel that never did gets one retry, not five). */
    private var pictured = false

    /** Backup copies and hiding channels that won't play; see [Backups]. */
    private val backups by lazy {
        Backups(
            context = this,
            scope = lifecycleScope,
            container = container,
            hooks =
                Backups.Hooks(
                    settings = { settings },
                    queue = { queue },
                    setQueue = { queue = it },
                    mediaItem = { it.toMediaItem(null) },
                    showing = ::updateOverlay,
                    status = { text -> if (text == null) statusView.visibility = View.GONE else showStatus(text) },
                    flash = ::flash,
                    casting = { cast?.casting == true },
                ),
        )
    }
    private lateinit var statusView: TextView
    private val audioCheck by lazy {
        AudioCheck { codec -> Toast.makeText(this, getString(R.string.player_no_sound, codec), Toast.LENGTH_LONG).show() }
    }
    private val timeshift by lazy {
        LiveTimeshift(
            context = this,
            scope = lifecycleScope,
            http = container.http,
            userAgent = { settings.userAgent.ifBlank { "Tuned/${BuildConfig.VERSION_NAME}" } },
            keepMinutes = { settings.liveRewindMinutes },
        )
    }
    private val frameRate by lazy { FrameRateMatch(this) { settings.matchFrameRate && cast?.casting != true } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One connection to the provider at a time: downloads and channel scans wait while anything plays.
        container.playerOpen.value = true
        container.offline.pause()
        buildViews()
        hideSystemBars()
        // Nothing to show if the channel can't be read: log it and close rather than sit on a black screen.
        lifecycleScope.launchSafely("Player", onError = { finish() }) {
            settings = container.settings.settings.first()
            if (settings.landscapePlayback) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            tools.applyFit(settings.videoFit)
            if (settings.playerGestures) setUpGestures()
            if (settings.autoReconnect) reconnectWhenOnline()
            val query = intent.getStringExtra(EXTRA_TUNE)
            if (query != null) {
                startTune(query)
                return@launchSafely
            }
            val id = intent.getLongExtra(EXTRA_ID, 0)
            val channel = container.db.items().get(id)
            val catchUpStart = intent.getLongExtra(EXTRA_FROM, 0)
            val item =
                if (channel != null && catchUpStart > 0) {
                    val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                    CatchUpLinks.item(container, channel, title, catchUpStart, intent.getLongExtra(EXTRA_UNTIL, 0))
                } else {
                    channel
                }
            if (item == null) {
                finish()
                return@launchSafely
            }
            // A recording plays on its own; next and previous would leave it for live channels.
            queue = if (item.id == CatchUpLinks.ID) listOf(item) else siblings(item)
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
        val back = backButton()
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
        setUpTools()
        statusView =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                visibility = View.GONE
                setBackgroundColor(0x99000000.toInt())
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
            }
        // TV remote buttons go to the tools before the player's own controls; see [PlayerTools.onKey].
        val root =
            object : FrameLayout(this) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean = tools.onKey(event) || super.dispatchKeyEvent(event)
            }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )
        root.addView(
            statusView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        tools.attach(root)
        skipIntro.attach(root)
        tuneNext.attach(root)
        levelBars.attach(root)
        // The title shows and hides with the controls, so a tap reveals everything and the video is otherwise bare.
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                overlay.visibility = visibility
                tools.onControls(visibility)
                tuneNext.onControls(visibility)
            },
        )
        setContentView(root)
    }

    private fun backButton() = ImageButton(this).apply {
        setImageResource(R.drawable.ic_arrow_back)
        contentDescription = getString(R.string.back)
        val ripple = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        setBackgroundResource(ripple.resourceId)
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    /** The buttons at the top right (channels, aspect, quality, info, lock); see [PlayerTools]. */
    private fun setUpTools() {
        tools =
            PlayerTools(
                activity = this,
                playerView = playerView,
                overlay = overlay,
                hooks =
                    PlayerTools.Hooks(
                        player = { player },
                        queue = { queue },
                        isLive = ::live,
                        touch = { gestures },
                        say = ::flash,
                        onFitChanged = { fit ->
                            lifecycleScope.launchSafely("Player") { container.settings.update { it.copy(videoFit = fit) } }
                        },
                        subtitles = subtitleTiming,
                        casting = { cast?.casting == true },
                    ),
            )
        overlay.addView(tools.toolbar)
        cast =
            CastControl(
                activity = this,
                player = { player },
                current = { player?.let { queue.getOrNull(it.currentMediaItemIndex) } },
                isLive = ::live,
                onStatus = { text -> if (text == null) statusView.visibility = View.GONE else showStatus(text) },
            ).also { c -> tools.addButton(c.button) }
    }

    @SuppressLint("ClickableViewAccessibility") // PlayerGestures calls performClick on every tap
    private fun setUpGestures() {
        val g =
            PlayerGestures(
                activity = this,
                playerView = playerView,
                isLive = ::live,
                onSeek = { forward ->
                    player?.let { p ->
                        if (forward) p.seekForward() else p.seekBack()
                        val seconds = (if (forward) settings.seekForwardMs else settings.seekBackMs) / 1_000
                        flash(getString(if (forward) R.string.gesture_forward else R.string.gesture_back, seconds))
                    }
                },
                onChannel = { next -> player?.let { p -> if (next) p.seekToNextMediaItem() else p.seekToPreviousMediaItem() } },
                bars = levelBars,
            )
        gestures = g
        playerView.setOnTouchListener(g)
    }

    private fun showStatus(text: String) {
        statusView.text = text
        statusView.visibility = View.VISIBLE
    }

    private var flashJob: Job? = null

    /** Shows [text] (or keeps what's there) for a moment, then hides it. */
    private fun flash(text: String?) {
        if (text != null) showStatus(text)
        flashJob?.cancel()
        flashJob =
            lifecycleScope.launchSafely("Player") {
                delay(FLASH_MS)
                if (autoTune?.searching != true && !backups.searching) statusView.visibility = View.GONE
            }
    }

    /**
     * When the connection comes back (Wi-Fi to mobile, a tunnel, a router restart), a stream that stopped on an error
     * starts again by itself, from where it was for films and episodes.
     */
    private fun reconnectWhenOnline() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    lifecycleScope.launchSafely("Player") {
                        val p = player ?: return@launchSafely
                        if (cast?.casting != true && (p.playerError != null || p.playbackState == Player.STATE_IDLE)) {
                            liveRetries = 0
                            flash(getString(R.string.player_reconnecting))
                            p.prepare()
                        }
                    }
                }
            }
        attempt("Player", "Couldn't watch the network") { cm.registerDefaultNetworkCallback(callback) }.onSuccess { reconnect = callback }
    }

    /** Live: the other channels in the same category, so next/previous flips channels. Shows: the show's episodes. */
    private suspend fun siblings(item: ItemEntity): List<ItemEntity> {
        val items = container.db.items()
        return when (ContentKind.fromCode(item.kind)) {
            ContentKind.LIVE -> items.channelsInGroup(item.groupName, settings.hiddenKeys(), settings.showAdult)
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
                    if (position == null) tuneNext.flash()
                },
                onGiveUp = { Toast.makeText(this, getString(R.string.tune_none, query), Toast.LENGTH_LONG).show() },
                onDead = backups::channelFailed,
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
        playerView.player = ViewPlayer(exo)
        playerView.setControllerShowTimeoutMs(settings.controlsTimeoutMs)
        playerView.setShowNextButton(queue.size > 1)
        playerView.setShowPreviousButton(queue.size > 1)
        // Live channels can rewind and catch up once paused (see [LiveTimeshift]); the buttons are greyed out until then.
        playerView.setShowFastForwardButton(!live || timeshift.enabled)
        playerView.setShowRewindButton(!live || timeshift.enabled)
        CaptionStyles.apply(requireNotNull(playerView.subtitleView), settings.captions)

        // Saved films and episodes play from the device.
        val local = container.offline.files(queue.map { it.id })
        // Offline with a saved episode: next and previous keep to the saved ones, which are all that can play.
        if (item.id in local && !container.connectivity.onlineNow()) {
            queue = queue.filter { it.id in local }
            playerView.setShowNextButton(queue.size > 1)
            playerView.setShowPreviousButton(queue.size > 1)
        }
        val mediaItems = queue.map { it.toMediaItem(local[it.id]) }
        val index = queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        val resumeAt = if (!live && settings.resumePlayback) resumePosition(item.id) else C.TIME_UNSET
        exo.setMediaItems(mediaItems, index, resumeAt)
        exo.pauseAtEndOfMediaItems = kind == ContentKind.SERIES && !settings.autoPlayNext
        val castNow = cast?.takeIf { it.casting }
        // Every connection on the account is in use on another device: opening one more could go over the limit, so
        // wait for the person to press play (Settings › Account shows the same numbers). A saved file opens no
        // connection, so it never waits.
        val full = if (castNow == null && queue[index].id !in local) container.account.fullElsewhere() else null
        if (castNow != null) {
            // Already casting: this goes to the TV, and the phone opens no stream of its own.
            castNow.load(queue[index], live, resumeAt)
        } else if (full != null) {
            showStatus(getString(R.string.account_full_wait, full.activeConnections ?: 0, full.maxConnections ?: 0))
            playerView.showController()
        } else {
            exo.prepare()
            exo.playWhenReady = true
        }
        updateOverlay(queue[index])
        if (!live) progressJob = lifecycleScope.launchSafely("Player") { saveProgressEvery(exo) }
        if (live && autoTune == null && item.id != CatchUpLinks.ID) backups.attach(exo)
    }

    private fun buildPlayer(live: Boolean): ExoPlayer {
        // Streams over OkHttp (through the live rewind copy on live TV), with the headers the playlist asks for;
        // saved downloads from the device.
        val agent = settings.userAgent.ifBlank { "Tuned/${BuildConfig.VERSION_NAME}" }
        val http =
            PlayerSources.factory(
                this,
                container.http,
                agent,
                headers = { uri -> uri.host?.let(streamHeaders::get).orEmpty() },
                network = { network -> if (live) timeshift.factory(network) else network },
            )
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
        // If a device's first decoder for a format fails to start, try its next one instead of giving up.
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        return ExoPlayer
            .Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors).setSubtitleParserFactory(subtitleTiming.parsers))
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
            .apply {
                addListener(listener(live))
                addListener(skipIntro.listener)
            }
    }

    private fun listener(live: Boolean) = object : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: Int,
        ) {
            val id = mediaItem?.mediaId?.toLongOrNull() ?: return
            val item = queue.firstOrNull { it.id == id } ?: return
            updateOverlay(item)
            if (cast?.casting == true) cast?.load(item, live, 0)
            liveRetries = 0
            pictured = false
            player?.let(timeshift::onChannelChanged)
            // Flipping to another channel ends the search for a copy of the last one.
            backups.onChannelChanged(id)
        }

        override fun onRenderedFirstFrame() {
            player
                ?.currentMediaItem
                ?.mediaId
                ?.toLongOrNull()
                ?.let {
                    pictured = true
                    backups.pictureShown(it)
                }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Playing again after a drop: the next drop gets the full set of retries.
            if (playbackState == Player.STATE_READY) liveRetries = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.w("Player", "Playback error: ${error.errorCodeName}", error)
            val p = player ?: return
            // Auto-tune: a channel that fails while being tried is dead; move straight on to the next one.
            val tuner = autoTune
            if (tuner != null && tuner.searching) {
                tuner.failCurrent()
                return
            }
            if (backups.searching) {
                backups.failCurrent()
                return
            }
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            // Streams drop now and then: try again a few times, with a short note, before saying anything else. A film or
            // episode picks up where it was (prepare keeps the position).
            val network =
                error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            // A live channel that never showed a picture gets one retry before its backup copies are tried, for speed.
            val tries = backups.retries(fresh = live && !pictured, usual = MAX_RETRIES)
            if (settings.autoReconnect && (live || network) && liveRetries < tries) {
                liveRetries++
                flash(getString(R.string.player_reconnecting_try, liveRetries, tries))
                lifecycleScope.launchSafely("Player") {
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
            // A live channel that died for good: play a backup copy of it if there is one.
            val sayError = {
                Toast.makeText(this@PlayerActivity, getString(R.string.player_error, error.errorCodeName), Toast.LENGTH_LONG).show()
            }
            if (!live || !backups.takeOver(p.currentMediaItem?.mediaId?.toLongOrNull(), onNone = sayError)) sayError()
        }

        override fun onTracksChanged(tracks: Tracks) {
            player?.let { audioCheck.onTracks(it, tracks) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            frameRate.onVideo(player?.videoFormat?.frameRate)
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
        lifecycleScope.launchSafely("Player") {
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

    /** What the on-screen controls drive: the phone's player, or the TV's play and pause while casting. */
    private inner class ViewPlayer(
        private val exo: ExoPlayer,
    ) : ForwardingPlayer(exo) {
        override fun prepare() {
            if (cast?.casting != true) super.prepare()
        }

        override fun play() {
            when {
                cast?.casting == true -> {
                    cast?.play()
                }

                playbackState == Player.STATE_IDLE -> {
                    // Held because the account was full, or stopped: pressing play starts it.
                    statusView.visibility = View.GONE
                    super.prepare()
                    super.play()
                }

                else -> {
                    super.play()
                }
            }
        }

        override fun pause() {
            when {
                cast?.casting == true -> cast?.pause()

                // A live channel pauses into the copy kept on the device, so it can carry on, rewind or catch up.
                live() && timeshift.shift(exo) -> Unit

                else -> super.pause()
            }
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
        val id =
            exo.currentMediaItem
                ?.mediaId
                ?.toLongOrNull()
                ?.takeIf { it != CatchUpLinks.ID } ?: return
        val duration = exo.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        container.db.library().saveProgress(HistoryEntity(id, exo.currentPosition, duration, System.currentTimeMillis()))
    }

    private fun live(): Boolean = queue.firstOrNull()?.kind == ContentKind.LIVE.code

    private fun ItemEntity.toMediaItem(file: File?): MediaItem {
        val link = StreamLink.split(url)
        if (file == null && link.headers.isNotEmpty()) {
            link.url
                .toUri()
                .host
                ?.let { streamHeaders[it] = link.headers }
        }
        return MediaItem
            .Builder()
            .setMediaId(id.toString())
            .setUri(file?.toUri() ?: link.url.toUri())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
            .build()
    }

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

    // The player handles rotation itself (configChanges in the manifest), so the guide is placed again here.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        tools.onScreenChanged()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView.useController = !isInPictureInPictureMode && !tools.locked
        if (isInPictureInPictureMode) {
            playerView.hideController()
            overlay.visibility = View.GONE
            tools.hidePanels()
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
            if (id != null && id != CatchUpLinks.ID && duration > 0 && !live()) {
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
        backups.release()
        timeshift.release()
        gestures?.release()
        tools.release()
        cast?.release()
        container.appScope.launch { container.offline.start() }
        reconnect?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        player?.release()
        player = null
        container.account.noStreamSince = System.currentTimeMillis()
        container.playerOpen.value = false
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TUNE = "tune"
        private const val EXTRA_FROM = "catchup_from"
        private const val EXTRA_UNTIL = "catchup_until"
        private const val EXTRA_TITLE = "catchup_title"
        private const val MAX_RETRIES = 5
        private const val FLASH_MS = 900L

        fun intent(
            context: Context,
            itemId: Long,
        ): Intent = Intent(context, PlayerActivity::class.java).putExtra(EXTRA_ID, itemId)

        fun start(
            context: Context,
            itemId: Long,
        ) {
            context.startActivity(intent(context, itemId))
        }

        /** Catch-up: the recording of [title] on channel [itemId], from [from] to [until]. */
        fun catchUp(
            context: Context,
            itemId: Long,
            title: String,
            from: Long,
            until: Long,
        ) {
            context.startActivity(
                intent(context, itemId).putExtra(EXTRA_FROM, from).putExtra(EXTRA_UNTIL, until).putExtra(EXTRA_TITLE, title),
            )
        }

        /** Auto-tune: find a working stream for [query] (a channel or an event) and play it. */
        fun tuneIntent(
            context: Context,
            query: String,
        ): Intent = Intent(context, PlayerActivity::class.java).putExtra(EXTRA_TUNE, query)

        /** Auto-tune: find a working channel showing [query] (a channel name or an event in the guide) and play it. */
        fun tune(
            context: Context,
            query: String,
        ) {
            context.startActivity(tuneIntent(context, query))
        }
    }
}
