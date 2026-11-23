package com.tunedplayer.app.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.PlayGate
import com.tunedplayer.app.core.Titles
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.ServerFlags
import com.tunedplayer.app.data.db.HistoryEntity
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.premium.Premium
import com.tunedplayer.app.ui.theme.playerAccent
import com.tunedplayer.app.widget.Widgets
import com.tunedplayer.app.work.TvHome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Full-screen playback. Nothing on screen but the video until a tap shows the standard Media3 controls: play and
 * pause, seek, previous and next (the next channel in the category, or the next episode), captions, and the
 * settings menu for audio language and speed (films and episodes only; see [ViewPlayer]). Built on Views, not
 * Compose, because PlayerView is Media3's own tested surface and starts fastest.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : FragmentActivity() {
    private var player: ExoPlayer? = null
    private val session =
        PlayerSession(
            context = this,
            enabled = { settings.mediaButtons },
            background = { settings.backgroundPlay },
            sessionActivity = {
                // Back to this player as it is: the same request, which the single-task player doesn't restart.
                PendingIntent.getActivity(this, 0, Intent(intent), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            },
        )

    /** Leaving the app or locking the screen keeps the sound playing; see [BackgroundPlay]. */
    private val background by lazy {
        BackgroundPlay(this, lifecycleScope, { settings.backgroundPlay }, { player }, { cast?.casting == true })
    }
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

    /** Picture size, frame rate and sound under the title; see [InfoPills]. */
    private val pills by lazy { InfoPills(this, { player }, { cast?.casting == true }) }
    private lateinit var tools: PlayerTools
    private var cast: CastControl? = null

    /** The phone's screen while a TV plays: a remote, not the stale frame; see [CastingScreen]. */
    private val castScreen by lazy { CastingScreen(this, { cast }) { AccessibleControls.timeoutMs(this, settings.controlsTimeoutMs) } }

    /** Saved copies in the queue, by item id: played from the device here, and served from the phone when casting. */
    private var savedFiles: Map<Long, File> = emptyMap()
    private val subtitleTiming = SubtitleTiming()
    private val progress by lazy { PlaybackProgress(container) }
    private val playUsage by lazy { PlayUsage(container.usage) }
    private val subtitleSearch by lazy {
        SubtitleSearch(this, container.subtitles, { player }, { queue }, { settings }, { cast?.casting == true }, ::flash)
    }
    private val reconnect by lazy {
        ReconnectWhenOnline(this) {
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
                    mediaItem = { it.toMediaItem(null, streamHeaders) },
                    showing = ::updateOverlay,
                    status = { text -> if (text == null) statusView.visibility = View.GONE else showStatus(text) },
                    flash = ::flash,
                    casting = { cast?.casting == true },
                ),
        )
    }

    /** The HQ button's copies of the live channel in other picture qualities; see [QualityCopies]. */
    private val qualityCopies: QualityCopies by lazy {
        QualityCopies(
            scope = lifecycleScope,
            tune = container.tune,
            hooks =
                QualityCopies.Hooks(
                    casting = { cast?.casting == true },
                    switchTo = { id ->
                        player?.let { backups.swapTo(it, id) }
                        backups.onChannelChanged(id)
                        qualityCopies.onChannel(id)
                    },
                    changed = { if (::tools.isInitialized) tools.refreshQuality() },
                ),
        )
    }

    /** Typing a channel number and flipping back; see [ChannelDial]. */
    private val dial by lazy {
        ChannelDial(
            scope = lifecycleScope,
            hooks =
                ChannelDial.Hooks(
                    find = { n -> container.db.channelNumbers().liveByNumber(n) },
                    get = { id -> container.db.items().get(id) },
                    jump = { item ->
                        val exo = player
                        val index = queue.indexOfFirst { it.id == item.id }
                        if (exo != null && index >= 0) {
                            exo.seekTo(index, C.TIME_UNSET)
                        } else if (exo != null) {
                            // Another group: its channels become next and previous. The same player, so one stream.
                            queue = playerSiblings(container.db, settings, item)
                            val at = queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                            exo.setMediaItems(queue.map { it.toMediaItem(null, streamHeaders) }, at, C.TIME_UNSET)
                            exo.prepare()
                            exo.playWhenReady = true
                            playerView.setShowNextButton(queue.size > 1)
                            playerView.setShowPreviousButton(queue.size > 1)
                        }
                    },
                    say = ::flash,
                    noSuchChannel = { n -> getString(R.string.player_no_channel_number, n) },
                ),
        )
    }
    private lateinit var statusView: StatusNote
    private val audioCheck by lazy {
        AudioCheck { codec -> Toast.makeText(this, getString(R.string.player_no_sound, codec), Toast.LENGTH_LONG).show() }
    }
    private val timeshift by lazy { playerTimeshift { settings } }

    private val jumpToLive by lazy {
        JumpToLive(
            activity = this,
            behind = { timeshift.behind && cast?.casting != true },
            goLive = { player?.let(timeshift::goLive) == true },
        )
    }

    private val recorder by lazy {
        LiveRecorder(
            this,
            LiveRecorder.Hooks(
                channel = {
                    player
                        ?.let { queue.getOrNull(it.currentMediaItemIndex) }
                        ?.takeIf { it.kind == ContentKind.LIVE.code && it.id != CatchUpLinks.ID }
                },
                uri = { player?.currentMediaItem?.localConfiguration?.uri },
                stream = timeshift::stream,
                casting = { cast?.casting == true },
                say = ::flash,
                locked = { !container.premium.unlocked.value },
                unlock = { Premium.open(this) },
            ),
        )
    }

    private val scrobblers by lazy {
        WatchScrobblers(
            container.trakt,
            container.simkl,
            container.appScope,
            current = { player?.let { queue.getOrNull(it.currentMediaItemIndex) } },
            elsewhere = { cast?.tvPosition() },
        )
    }

    private val frameRate by lazy { FrameRateMatch(this) { settings.matchFrameRate && cast?.casting != true } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One connection to the provider at a time: downloads and channel scans wait while anything plays.
        container.playerOpen.value = true
        container.offline.pause()
        // A cast carries on while the app is in the background; downloads keep waiting while a TV plays.
        container.tvCasting.watch()
        // Back keeps regular content playing in a small window over the app; before the tools' own Back, so they come first.
        MiniPlayer.Screen(this, { settings }, { cast?.casting == true }, { player }) { playerView }
        buildViews()
        hideSystemBars(window)
        // Nothing to show if the channel can't be read: log it and close rather than sit on a black screen.
        lifecycleScope.launchSafely("Player", onError = { finish() }) {
            settings = container.settings.settings.first()
            if (settings.landscapePlayback) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            tools.applyFit(settings.videoFit)
            tintScrubber(playerView, playerAccent(this@PlayerActivity, settings.accent, settings.dynamicColour).also(castScreen::tint))
            if (settings.playerGestures) setUpGestures()
            if (settings.autoReconnect) reconnect.start()
            val query = intent.getStringExtra(EXTRA_TUNE)
            if (query != null) {
                startTune(query)
                return@launchSafely
            }
            val id = intent.getLongExtra(EXTRA_ID, 0)
            val channel = container.db.items().get(id)
            val catchUpStart = intent.getLongExtra(EXTRA_FROM, 0)
            val recording = intent.getLongExtra(EXTRA_RECORDING, 0)
            val item =
                when {
                    recording != 0L -> {
                        container.recordings.get(recording)?.let(container.recordings::item)
                    }

                    channel != null && catchUpStart > 0 -> {
                        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                        CatchUpLinks.item(container, channel, title, catchUpStart, intent.getLongExtra(EXTRA_UNTIL, 0))
                    }

                    else -> {
                        channel
                    }
                }
            if (item == null) {
                finish()
                return@launchSafely
            }
            // The tile, the widget, TV home cards and other apps' play intents skip the lists' filters.
            if (channel != null && recording == 0L && !mayPlay(channel)) {
                Toast.makeText(this@PlayerActivity, R.string.player_not_allowed, Toast.LENGTH_LONG).show()
                finish()
                return@launchSafely
            }
            // A recording plays on its own; next and previous would leave it for live channels.
            queue = if (item.id == CatchUpLinks.ID) listOf(item) else playerSiblings(container.db, settings, item)
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
        titleView = titleText(this)
        subtitleView = subtitleText(this)
        // Back sits with the title, so it shows whenever the controls do (system back and gestures work too).
        val back = backButton(this) { onBackPressedDispatcher.onBackPressed() }
        val titles = titleColumn(this, titleView, subtitleView)
        overlay = topBar(this, back, titles)
        // Before the tools: opening the player mid-cast shows its note as the cast button is set up.
        statusView = StatusNote(this)
        setUpTools()
        // The time of day at the top right, after the tools, and the stream's pills under the title.
        addGlance(overlay, titles, pills)
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
            // Above the centre, so a lasting note ("Playing on SHIELD") doesn't cover the play button (Melvin, 2026-11-19).
            FrameLayout
                .LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                .apply { bottomMargin = (STATUS_RAISE_DP * resources.displayMetrics.density).toInt() },
        )
        attachFeatures(root)
        // The title shows and hides with the controls, so a tap reveals everything and the video is otherwise bare.
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                overlay.visibility = visibility
                if (visibility == View.VISIBLE) pills.update()
                tools.onControls(visibility)
                recorder.onControls(visibility)
                jumpToLive.onControls(visibility)
                tuneNext.onControls(visibility)
            },
        )
        setContentView(root)
        // Opened while a TV plays: the casting screen from the start.
        if (cast?.tvName != null) castChanged()
    }

    /** The player features drawn over the video, each in its own file. */
    private fun attachFeatures(root: FrameLayout) {
        tools.attach(root)
        recorder.attach(root)
        skipIntro.attach(root)
        jumpToLive.attach(root)
        tuneNext.attach(root)
        levelBars.attach(root)
        castScreen.attach(root, playerView)
    }

    /** The buttons at the top right (channels, flip back, quality, fit, lock); see [PlayerTools]. */
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
                        dial = dial,
                        tv = TvHome.isTv(this),
                    ),
                copies = qualityCopies,
            )
        overlay.addView(tools.toolbar)
        val captionButtons = listOf(subtitleSearch.button, tools.subtitleTimingButton)
        if (!placeBesideCaptions(playerView, captionButtons)) captionButtons.reversed().forEach(tools::addButton)
        cast =
            CastControl(
                activity = this,
                player = { player },
                current = { player?.let { queue.getOrNull(it.currentMediaItemIndex) } },
                isLive = ::live,
                savedFile = { id -> savedFiles[id] },
                // From the moment a TV is picked, its steps and problems show on the casting screen.
                onStatus =
                    castScreen.notesOr { text, busy ->
                        if (text ==
                            null
                        ) {
                            statusView.visibility = View.GONE
                        } else {
                            statusView.show(text, busy)
                        }
                    },
                subtitleOffsetMs = { subtitleTiming.offsetMs },
                onTvChanged = ::castChanged,
                resolve = container::resolveStreamAsync,
            ).also { c -> if (ServerFlags.now.value.cast) tools.addButton(c.button) } // the admin's `cast` switch
        tools.addButton(recorder.button)
    }

    @SuppressLint("ClickableViewAccessibility") // PlayerGestures performs the click once a single tap is confirmed
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

    private fun showStatus(text: String) = statusView.show(text, busy = false)

    /** A cast began or ended, or the TV changed: the controls show the TV, and what can't work there is greyed out. */
    private fun castChanged() {
        (playerView.player as? ViewPlayer)?.tvChanged()
        // Speed and sound tracks don't apply to the TV; Media3 enables the button again once the cast ends.
        if (cast?.casting == true) playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.usable(false)
        castScreen.follow(player?.let { queue.getOrNull(it.currentMediaItemIndex) })
        tools.onControls(View.VISIBLE)
        recorder.onControls(View.VISIBLE)
        pills.update()
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

    private fun mayPlay(item: ItemEntity): Boolean = PlayGate.allowed(
        item.adult,
        item.groupName,
        item.name,
        live = item.kind == ContentKind.LIVE.code,
        showAdult = settings.showAdult,
        kidsProfile = settings.kidsProfile,
    )

    private suspend fun start(item: ItemEntity) {
        val kind = ContentKind.fromCode(item.kind)
        val live = kind == ContentKind.LIVE
        val exo = buildPlayer(live)
        player = exo
        scrobblers.attach(exo)
        val shown = ViewPlayer(exo, { cast?.tv }, { statusView.visibility = View.GONE }, ::pauseLive, ::live)
        playerView.player = shown
        // Media buttons and the Assistant act through the same player as the on-screen controls.
        session.attach(shown)
        playerView.setControllerShowTimeoutMs(castScreen.timeoutMs())
        playerView.setShowNextButton(queue.size > 1)
        playerView.setShowPreviousButton(queue.size > 1)
        // Live channels can rewind and catch up once paused (see [LiveTimeshift]); the buttons are greyed out until then.
        playerView.setShowFastForwardButton(!live || timeshift.enabled)
        playerView.setShowRewindButton(!live || timeshift.enabled)
        CaptionStyles.apply(requireNotNull(playerView.subtitleView), settings.captions)

        // Saved films and episodes play from the device.
        val local = container.offline.files(queue.map { it.id })
        savedFiles = local
        // Offline with a saved episode: next and previous keep to the saved ones, which are all that can play.
        if (item.id in local && !container.connectivity.onlineNow()) {
            queue = queue.filter { it.id in local }
            playerView.setShowNextButton(queue.size > 1)
            playerView.setShowPreviousButton(queue.size > 1)
        }
        val mediaItems = subtitleSearch.withSaved(exo, queue, item.id, queue.map { it.toMediaItem(local[it.id], streamHeaders) })
        val index = queue.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        val resumeAt = if (!live && settings.resumePlayback) progress.resumePosition(item.id) else C.TIME_UNSET
        exo.setMediaItems(mediaItems, index, resumeAt)
        exo.pauseAtEndOfMediaItems = kind == ContentKind.SERIES && !settings.autoPlayNext
        val castNow = cast?.takeIf { it.casting }
        // Every connection on the account is in use on another device: opening one more could go over the limit, so
        // wait for the person to press play (Settings › Account shows the same numbers). A saved file opens no
        // connection, so it never waits.
        val full = if (castNow == null && !playsFromDevice(queue[index], local)) container.account.fullElsewhere() else null
        if (castNow != null) {
            // Already casting: this goes to the TV, and the phone opens no stream of its own.
            castNow.load(queue[index], live, resumeAt)
        } else if (full != null) {
            showStatus(getString(R.string.account_full_wait, full.activeConnections ?: 0, full.maxConnections ?: 0))
            playerView.showController()
        } else {
            // A download stopped as the player opened hangs up first, so the provider never sees two connections. It
            // takes a moment at most, and only when a download was running.
            awaitHangUp()
            exo.prepare()
            exo.playWhenReady = true
        }
        updateOverlay(queue[index])
        if (live) dial.onChannel(queue[index].id)
        if (!live) progressJob = lifecycleScope.launchSafely("Player") { progress.saveProgressEvery(exo) }
        if (live && autoTune == null && item.id != CatchUpLinks.ID) backups.attach(exo)
    }

    /** A saved download or a recording, which play from the device and open no connection. */
    private fun playsFromDevice(
        item: ItemEntity,
        saved: Map<Long, *>,
    ): Boolean = item.id in saved || item.url.startsWith("file:")

    private fun buildPlayer(live: Boolean): ExoPlayer {
        // Streams over OkHttp (through the live rewind copy on live TV), with the headers the playlist asks for;
        // saved downloads from the device.
        val agent = settings.playerAgent()
        val http =
            PlayerSources.factory(
                this,
                container.http,
                agent,
                headers = { uri -> uri.host?.let(streamHeaders::get).orEmpty() },
                network = { network -> if (live) timeshift.factory(network) else network },
                portal = container::resolveStream,
            )
        // Decoder fallback, and the sound delay from Settings › Player (lip sync); see [TunedRenderersFactory].
        val renderers = TunedRenderersFactory(this) { settings.audioDelayMs }
        return ExoPlayer
            .Builder(this, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(http, PlayerSetup.extractors()).setSubtitleParserFactory(subtitleTiming.parsers),
            ).setLoadControl(PlayerSetup.loadControl(settings))
            .setTrackSelector(PlayerSetup.trackSelector(this, settings))
            .setSeekBackIncrementMs(settings.seekBackMs)
            .setSeekForwardIncrementMs(settings.seekForwardMs)
            .setAudioAttributes(PlayerSetup.audio, true)
            .setHandleAudioBecomingNoisy(true)
            // Playing on with the screen off needs the CPU and Wi-Fi kept awake (only while it plays).
            .setWakeMode(if (settings.backgroundPlay) C.WAKE_MODE_NETWORK else C.WAKE_MODE_NONE)
            .build()
            .apply {
                addListener(listener(live))
                addListener(background.listener)
                addListener(skipIntro.listener)
                addListener(subtitleSearch.listener)
                addListener(pills.listener)
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
            // Subtitles picked while casting reload the same video: the TV gets it again with them, where it was.
            val c = cast?.takeIf { it.casting }
            val again = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            if (c != null) c.load(item, live, if (again) c.positionOnTv(item) else 0)
            liveRetries = 0
            pictured = false
            player?.let(timeshift::onChannelChanged)
            if (live) dial.onChannel(id)
            // Flipping to another channel ends the search for a copy of the last one.
            backups.onChannelChanged(id)
            qualityCopies.onChannel(if (live && id != CatchUpLinks.ID) id else null)
        }

        override fun onRenderedFirstFrame() {
            player
                ?.currentMediaItem
                ?.mediaId
                ?.toLongOrNull()
                ?.let { id ->
                    pictured = true
                    backups.pictureShown(id)
                    playUsage.pictured(queue.firstOrNull { it.id == id }, id in savedFiles, cast?.casting == true)
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
            val failedId = p.currentMediaItem?.mediaId?.toLongOrNull()
            playUsage.failed(queue.firstOrNull { it.id == failedId }, error)
            if (!live || !backups.takeOver(failedId, onNone = sayError)) sayError()
        }

        override fun onTracksChanged(tracks: Tracks) {
            player?.let { audioCheck.onTracks(it, tracks) }
            tools.onTracks(tracks)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            frameRate.onVideo(player?.videoFormat?.frameRate)
            tools.refreshFitScale()
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
                setPictureInPictureParams(pipParams(player, playerView, settings.pictureInPicture))
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
                setPictureInPictureParams(pipParams(player, playerView, settings.pictureInPicture))
            }
        }
    }

    private fun updateOverlay(item: ItemEntity) {
        // The name without the provider's language tag ("EN - "), and no category beneath it (Melvin, 2026-11-18). A
        // catch-up recording keeps its channel's name there.
        titleView.text = Titles.display(item.name)
        subtitleView.text = if (item.id == CatchUpLinks.ID) item.groupName else ""
        // A new channel or episode on the TV: the casting screen shows its art.
        if (castScreen.shown) castChanged()
        // The episode's TMDB name, from the device at once when the show's page was opened, else one cached
        // TMDB request. Playback never waits for it, and it stays out of its own function because detekt caps
        // this class at 30.
        val show = item.showName
        val season = item.season
        val episode = item.episode
        if (show != null && season != null && episode != null) {
            lifecycleScope.launchSafely("Player") {
                val info = container.metadata.cachedEpisode(show, season, episode) ?: container.metadata.episode(show, season, episode)
                // Moved on to the next episode meanwhile: that one has its own lookup.
                if (info?.name != null && player?.currentMediaItem?.mediaId == item.id.toString()) {
                    titleView.text = info.name
                    subtitleView.text = getString(R.string.continue_episode, Titles.display(show), season, episode)
                }
            }
        }
        if (ContentKind.fromCode(item.kind) == ContentKind.LIVE) progress.rememberChannel(item.id)
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
                ).joinToString("  ·  ")
        }
    }

    private fun live(): Boolean = queue.firstOrNull()?.kind == ContentKind.LIVE.code

    /** A live channel pauses into the copy kept on the device, so it can carry on, rewind or catch up. */
    private fun pauseLive(exo: ExoPlayer): Boolean = live() && timeshift.shift(exo)

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters picture-in-picture by itself (setAutoEnterEnabled); older versions need asking.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && settings.pictureInPicture && player?.isPlaying == true) {
            enterPictureInPictureMode(pipParams(player, playerView, settings.pictureInPicture))
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
        recorder.hideMark(isInPictureInPictureMode)
        jumpToLive.hide(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            playerView.hideController()
            overlay.visibility = View.GONE
            tools.hidePanels()
        }
    }

    /**
     * The player is single-task, so picking another channel while it is open (from the app behind picture-in-picture,
     * the widget, the quick-settings tile or a game alert) arrives here rather than in [onCreate]. It used to be
     * ignored and the old channel kept playing. Now the player starts over on the new pick; Android destroys this
     * one first, so its stream closes before the next opens.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (PlayRequest.of(intent) == PlayRequest.of(getIntent())) return
        setIntent(intent)
        recreate()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars(window)
    }

    override fun onStart() {
        super.onStart()
        background.onStart()
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
                    TvHome.schedule(applicationContext) // the TV home screen's Continue watching row
                    Widgets.refresh(applicationContext) // and the Continue watching widget
                }
            }
        }
        // Leaving the app, or closing the picture-in-picture window, stops the activity: playback ends there, unless
        // it carries on in the background. (In picture-in-picture the activity is only paused, so it keeps playing.)
        if (!isChangingConfigurations && !background.keepPlaying(isInPictureInPictureMode)) {
            player?.pause()
            finish()
        }
    }

    override fun onDestroy() {
        progressJob?.cancel()
        scrobblers.release()
        autoTune?.release()
        backups.release()
        recorder.release()
        timeshift.release()
        gestures?.release()
        tools.release()
        dial.release()
        cast?.release(leaving = isFinishing)
        reconnect.stop()
        background.release()
        session.release()
        // The stream closes before downloads carry on, so the provider never sees two connections.
        player?.release()
        player = null
        container.account.noStreamSince = System.currentTimeMillis()
        MiniPlayer.playerClosed(container)
        super.onDestroy()
    }

    /** A download or scheduled recording stopped as the player opened hangs up first: never two connections. */
    private suspend fun awaitHangUp() {
        withTimeoutOrNull(DOWNLOAD_STOP_MS) {
            combine(container.downloadOpen, container.recordingOpen) { down, rec -> down || rec }.first { !it }
        }
    }

    companion object {
        internal const val EXTRA_ID = "id"

        /** How far the status note sits above the centre: clear of the play button, below the title. */
        private const val STATUS_RAISE_DP = 72
        internal const val EXTRA_TUNE = "tune"
        internal const val EXTRA_FROM = "catchup_from"
        private const val EXTRA_UNTIL = "catchup_until"
        private const val EXTRA_TITLE = "catchup_title"
        internal const val EXTRA_RECORDING = "recording"
        private const val MAX_RETRIES = 5
        private const val FLASH_MS = 900L
        private const val DOWNLOAD_STOP_MS = 2_000L

        fun intent(
            context: Context,
            itemId: Long,
        ): Intent = Intent(context, PlayerActivity::class.java).putExtra(EXTRA_ID, itemId)

        fun start(
            context: Context,
            itemId: Long,
        ) {
            MiniPlayer.open(context, intent(context, itemId))
        }

        /** A recording of a live channel ([com.tunedplayer.app.data.Recordings]), from the device. */
        fun recording(
            context: Context,
            id: Long,
        ) {
            MiniPlayer.open(context, Intent(context, PlayerActivity::class.java).putExtra(EXTRA_RECORDING, id))
        }

        /** Catch-up: the recording of [title] on channel [itemId], from [from] to [until]. */
        fun catchUp(
            context: Context,
            itemId: Long,
            title: String,
            from: Long,
            until: Long,
        ) {
            MiniPlayer.open(
                context,
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
            MiniPlayer.open(context, tuneIntent(context, query))
        }
    }
}
