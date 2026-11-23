package com.tunedplayer.app.player

import android.content.Context
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.tunedplayer.app.R
import com.tunedplayer.app.core.CastCodecs
import com.tunedplayer.app.core.CastHls
import com.tunedplayer.app.core.CastMedia
import com.tunedplayer.app.core.FileServer
import com.tunedplayer.app.core.MediaServer
import com.tunedplayer.app.core.Stalker
import com.tunedplayer.app.core.StreamLink
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Chromecast from the player. When a cast starts, the phone stops its own stream (so only one connection to the
 * provider is ever open) and the TV plays the channel; changing channel while casting changes it on the TV, and ending
 * the cast picks the stream back up on the phone. DLNA TVs show in the same picker ([DlnaControl]); phones without
 * Google Play services list only those.
 *
 * Every step goes to the log (Settings › Updates and advanced › Logs, logins masked): the session, the address and type sent, the
 * TV's answer and any error it reports. A load the TV turns down is tried again after a pause ([CastMedia.retryDelayMs]),
 * since a provider can still count the phone's connection for a moment after it closes; if every try fails, the note
 * over the video says so.
 */
class CastControl(
    private val activity: FragmentActivity,
    private val player: () -> ExoPlayer?,
    private val current: () -> ItemEntity?,
    private val isLive: () -> Boolean,
    /** The saved copy of a film or episode, by item id, which the TV is given from the phone instead of the stream. */
    private val savedFile: (Long) -> File?,
    /**
     * Shows a lasting note over the video ("Playing on Living Room TV"), or hides it with null. Busy notes ("Connecting
     * to SHIELD…", "Starting on SHIELD…") show a spinner beside them until the TV plays, fails or the cast ends.
     */
    private val onStatus: (text: String?, busy: Boolean) -> Unit,
    /** The subtitle timing set in the player, for the subtitles sent to a Chromecast. */
    private val subtitleOffsetMs: () -> Long = { 0L },
    /** The TV started, paused, moved or changed its subtitles, or a cast began or ended: the controls look again. */
    private val onTvChanged: () -> Unit = {},
    /** A Stalker portal's channel turned into its stream (`data/StalkerLinks`), which the TV can open itself. */
    private val resolve: suspend (String) -> String = { it },
) {
    @Suppress("DEPRECATION") // the Task-based replacement needs an executor and a callback for the same result
    private val context: CastContext? =
        attempt("Cast", "Cast isn't available on this device") { CastContext.getSharedInstance(activity) }.getOrNull()
    private var session: CastSession? = null
    private var lastPositionMs = 0L
    private var retry: Job? = null
    private var files: FileServer? = null
    private var preparing: Job? = null
    private var lastLoad: Pair<Long, Long>? = null
    private var stream: CastStream? = null
    private var streamFolder: String? = null
    private var lastPlayerState = MediaStatus.PLAYER_STATE_UNKNOWN

    /** The TV being cast to, from the moment one is picked until the cast ends; the casting screen shows while it is set. */
    var tvName: String? = null
        private set

    /** A cast left with nothing playing, ended as the player opened: its end doesn't move the phone's player. */
    private var stopped: CastSession? = null

    /** A DLNA TV says nothing back, so the controls show what was last asked of it. */
    private var dlnaPaused = false

    private val subtitles =
        CastSubtitles(
            player = player,
            serve = { name, made -> serve(name, made) },
            unshare = { url -> files?.unshare(pathOf(url)) },
            offsetMs = subtitleOffsetMs,
        )

    /** The TV, as the player's controls see it ([ViewPlayer]): its time, play and pause, seeking and subtitles. */
    val tv: TvControls =
        object : TvControls {
            override val casting: Boolean get() = this@CastControl.casting

            override fun state(): TvState = if (dlna.active) {
                TvState(playWhenReady = !dlnaPaused, playbackState = Player.STATE_READY)
            } else {
                CastSubtitles.state(session?.remoteMediaClient, isLive(), subtitles.sent)
            }

            override fun play() = this@CastControl.play()

            override fun pause() = this@CastControl.pause()

            override fun seekTo(positionMs: Long) {
                val client = session?.remoteMediaClient ?: return
                AppLog.i(TAG) { "Seeking the TV to ${positionMs / MS_PER_S} s" }
                client.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
                onTvChanged()
            }

            override fun showSubtitles(on: Boolean) {
                AppLog.i(TAG) { if (on) "Subtitles on" else "Subtitles off" }
                subtitles.show(session?.remoteMediaClient, on)
                onTvChanged()
            }
        }

    /** What the TV says about the media: logged, so a cast that shows "No media selected" says why. */
    private val mediaCallback =
        object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                onTvChanged()
                val status = session?.remoteMediaClient?.mediaStatus ?: return
                if (status.playerState == lastPlayerState) return
                lastPlayerState = status.playerState
                if (status.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                    AppLog.w(TAG, "The TV stopped with an error")
                } else {
                    AppLog.i(TAG) { "TV is ${playerState(status.playerState)}" }
                }
                // The TV shows the picture: the spinner's work is done.
                val state = status.playerState
                if (state == MediaStatus.PLAYER_STATE_PLAYING || state == MediaStatus.PLAYER_STATE_PAUSED) playing()
            }

            override fun onMediaError(error: MediaError) {
                AppLog.w(
                    TAG,
                    "The TV reported a media error: ${error.type}, reason ${error.reason}, code ${error.detailedErrorCode}" +
                        " (${CastCodecs.describeError(error.detailedErrorCode)}), extra ${error.customData}",
                )
                // Say so over the video rather than leave "Playing on SHIELD" showing over nothing.
                session?.let { onStatus(activity.getString(R.string.cast_failed, name(it)), false) }
            }
        }

    /** The TV's volume changed (from the casting screen, the TV's remote or the phone's volume keys). */
    private val volumeListener =
        object : Cast.Listener() {
            override fun onVolumeChanged() = onTvChanged()
        }

    /** The standard cast button, listing Chromecasts (with Play services) and DLNA TVs together. */
    val button: MediaRouteButton =
        SafeRouteButton(ContextThemeWrapper(activity, R.style.Theme_Tuned_CastButton)).also { button ->
            if (context != null) CastButtonFactory.setUpMediaRouteButton(activity.applicationContext, button)
            button.routeSelector =
                MediaRouteSelector
                    .Builder()
                    .addSelector(button.routeSelector)
                    .addSelector(DlnaRouteProvider.selector)
                    .build()
        }

    private val dlna = DlnaControl(activity, activity, onStarted = ::handOff, onPlaying = ::playing, onEnded = ::ended)

    val casting get() = session?.isConnected == true || dlna.active

    private val listener =
        object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(
                s: CastSession,
                sessionId: String,
            ) = started(s)

            override fun onSessionResumed(
                s: CastSession,
                wasSuspended: Boolean,
            ) = started(s)

            override fun onSessionEnding(s: CastSession) {
                if (s === stopped) return
                lastPositionMs = s.remoteMediaClient?.approximateStreamPosition ?: 0L
            }

            override fun onSessionEnded(
                s: CastSession,
                error: Int,
            ) {
                AppLog.i(TAG) { "Cast ended: ${code(error)}" }
                if (s === stopped) {
                    stopped = null
                    return
                }
                ended()
            }

            override fun onSessionStartFailed(
                s: CastSession,
                error: Int,
            ) {
                AppLog.w(TAG, "Couldn't connect to ${name(s)}: ${code(error)}")
                notConnected(s)
            }

            override fun onSessionResumeFailed(
                s: CastSession,
                error: Int,
            ) {
                AppLog.w(TAG, "Couldn't reconnect to ${name(s)}: ${code(error)}")
                notConnected(s)
            }

            override fun onSessionStarting(s: CastSession) {
                AppLog.i(TAG) { "Connecting to ${describe(s)}" }
                connecting(s)
            }

            override fun onSessionResuming(
                s: CastSession,
                sessionId: String,
            ) {
                AppLog.i(TAG) { "Reconnecting to ${describe(s)}" }
                connecting(s)
            }

            override fun onSessionSuspended(
                s: CastSession,
                reason: Int,
            ) = AppLog.w(TAG, "Connection to ${name(s)} paused (reason $reason)")
        }

    init {
        context?.sessionManager?.addSessionManagerListener(listener, CastSession::class.java)
        context
            ?.sessionManager
            ?.currentCastSession
            ?.takeIf { it.isConnected }
            ?.let(::carryOnOrStop)
        dlna.resume()
    }

    /**
     * Opening the player while the TV still plays (or is paused on) the last video carries on casting. A cast that was
     * stopped on the TV leaves its app open with nothing playing; the next show used to go to it anyway (Melvin,
     * 2026-11-19: "when you're casting a show and you stop it, and you start another show, don't keep trying to cast"),
     * so the cast ends instead and the show plays on the phone.
     */
    private fun carryOnOrStop(s: CastSession) {
        val state = s.remoteMediaClient?.mediaStatus?.playerState
        if (CastResume.carriesOn(state)) {
            attach(s)
            tvName = name(s)
            playing()
            onTvChanged()
            return
        }
        AppLog.i(TAG) { "${name(s)} is connected but ${state?.let(::playerState) ?: "has nothing"}: ending the cast, playing here" }
        stopped = s
        context?.sessionManager?.endCurrentSession(true)
    }

    /** A TV was picked: the TV's app takes a few seconds to open, so the note says it is on its way. */
    private fun connecting(s: CastSession) {
        tvName = name(s)
        onStatus(activity.getString(R.string.cast_connecting, name(s)), true)
        onTvChanged()
    }

    /** The TV couldn't be reached (or the person cancelled): the phone carries on, so the note goes. */
    private fun notConnected(s: CastSession) {
        onStatus(null, false)
        if (session != null) return
        tvName = null
        onTvChanged()
        Toast.makeText(activity, activity.getString(R.string.cast_connect_failed, name(s)), Toast.LENGTH_LONG).show()
    }

    /** The TV is showing it: the step's note goes, and the casting screen says what the TV does ("Playing", "Paused"). */
    private fun playing() {
        if (tvName == null) return
        onStatus(null, false)
    }

    /** The TV takes over: the phone lets go of its stream so the provider sees a single connection. */
    private fun started(s: CastSession) {
        AppLog.i(TAG) { "Connected to ${describe(s)}" }
        attach(s)
        handOff(name(s))
        onTvChanged()
    }

    private fun attach(s: CastSession) {
        detach()
        session = s
        lastPlayerState = MediaStatus.PLAYER_STATE_UNKNOWN
        s.remoteMediaClient?.registerCallback(mediaCallback)
        attempt(TAG, "Couldn't follow the TV's volume") { s.addCastListener(volumeListener) }
    }

    private fun detach() {
        val s = session ?: return
        s.remoteMediaClient?.unregisterCallback(mediaCallback)
        attempt(TAG, "Couldn't stop following the TV's volume") { s.removeCastListener(volumeListener) }
    }

    /** The TV's volume from 0 to 1, or null when the phone can't change it (a DLNA TV, or not connected yet). */
    val volume: Float?
        get() {
            if (dlna.active) return null
            val s = session?.takeIf { it.isConnected } ?: return null
            return attempt(TAG, "Couldn't read the TV's volume") { s.getVolume().toFloat() }.getOrNull()
        }

    /** The casting screen's volume slider: sets the TV's volume, from 0 to 1. */
    fun setVolume(level: Float) {
        val s = session?.takeIf { it.isConnected } ?: return
        attempt(TAG, "Couldn't change the TV's volume") { s.setVolume(level.coerceIn(0f, 1f).toDouble()) }
    }

    /**
     * Stop casting, from the casting screen: the TV stops and its app closes, and the phone plays on from where the TV
     * was, as when the cast is ended from the cast button.
     */
    fun stop() {
        AppLog.i(TAG) { "Stop casting pressed for ${tvName ?: "the TV"}" }
        if (dlna.active) {
            dlna.disconnect()
        } else {
            attempt(TAG, "Couldn't end the cast") { context?.sessionManager?.endCurrentSession(true) }
        }
    }

    /**
     * The phone lets go of its stream before the TV opens its own, so the provider never sees two connections at once
     * (an account allowed one turns the second away, and the TV is left with nothing to play).
     */
    private fun handOff(name: String) {
        lastPositionMs = 0L
        tvName = name
        dlnaPaused = false
        val exo = player()
        val item = current()
        if (exo == null || item == null) {
            onStatus(activity.getString(R.string.cast_playing_on, name), false)
            return AppLog.w(TAG, if (exo == null) "No player to hand over to the TV" else "Nothing playing to send to the TV")
        }
        // Until the TV plays it: a saved file is got ready first, and the TV's player takes a moment to start.
        onStatus(activity.getString(R.string.cast_starting, name), true)
        val positionMs = exo.currentPosition
        exo.stop()
        load(item, isLive(), positionMs)
        onTvChanged()
    }

    /** Back on the phone, from where the TV was for films and episodes. */
    private fun ended() {
        retry?.cancel()
        preparing?.cancel()
        lastLoad = null
        stopServing()
        detach()
        session = null
        tvName = null
        subtitles.stop()
        onStatus(null, false)
        onTvChanged()
        val exo = player() ?: return
        if (!isLive() && lastPositionMs > 0) exo.seekTo(lastPositionMs)
        exo.prepare()
        exo.play()
    }

    /**
     * Plays [item] on the TV, from [positionMs] for films and episodes (an unknown position, Media3's TIME_UNSET, is
     * the start). Asking for the same item again within a moment does nothing: opening the player while a cast starts
     * used to send it twice, and the second load cancelled the first.
     */
    fun load(
        item: ItemEntity,
        live: Boolean,
        positionMs: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (item.id == lastLoad?.first && now - (lastLoad?.second ?: 0L) < SAME_LOAD_MS) {
            return AppLog.i(TAG) { "Already sending this to the TV" }
        }
        lastLoad = item.id to now
        retry?.cancel()
        preparing?.cancel()
        closeStream()
        subtitles.stop()
        val from = positionMs.coerceAtLeast(0)
        // A saved film or episode plays from the phone, never the provider ("saved before streamed").
        val saved = savedFile(item.id)
        if (saved == null && (Stalker.isLink(item.url) || MediaServer.isLink(item.url))) {
            // The TV opens the stream itself, so it gets the real link rather than the stored one.
            preparing =
                activity.lifecycleScope.launchSafely(TAG) {
                    val url = attempt(TAG, "No stream to cast") { resolve(item.url) }.getOrNull() ?: return@launchSafely
                    lastLoad = null
                    load(item.copy(url = url), live, positionMs)
                }
            return
        }
        if (saved == null) {
            if (dlna.active) {
                dlna.load(item, live, from)
            } else {
                send(Request(item, live, from, CastMedia.forCast(StreamLink.address(item.url), live), attempt = 1))
            }
        } else if (dlna.active) {
            val url = serve(saved) ?: return noWifi(saved)
            dlna.load(item, live, from, url)
        } else {
            sendSaved(item, saved, from)
        }
    }

    /**
     * A Chromecast gets a saved file as HLS the phone makes piece by piece while the TV plays ([CastStream]): the
     * video as it is and the sound as stereo AAC, starting at once with nothing copied first. Video an MP4 can't hold
     * goes as the file itself. DLNA TVs take MKV and Dolby sound, so they get the file as it is.
     */
    private fun sendSaved(
        item: ItemEntity,
        saved: File,
        positionMs: Long,
    ) {
        val s = session ?: return AppLog.w(TAG, "Not connected to a TV, so nothing was sent")
        preparing =
            activity.lifecycleScope.launchSafely(TAG) {
                // Not cancellable, so a stream opened just as another load starts is still closed below.
                val made =
                    withContext(Dispatchers.IO + NonCancellable) {
                        attempt(TAG, "Couldn't read ${saved.name} to stream it") { CastStream.open(saved) }.getOrNull()
                    }
                if (!isActive || session !== s) {
                    made?.let { closeLater(it) }
                    return@launchSafely
                }
                stream = made
                val media =
                    if (made != null) {
                        val folder = serve(saved.nameWithoutExtension, made) ?: return@launchSafely noWifi(saved)
                        streamFolder = pathOf(folder)
                        CastMedia.phoneStream(folder + CastHls.PLAYLIST)
                    } else {
                        CastMedia.Media(serve(saved) ?: return@launchSafely noWifi(saved), CastMedia.mimeFor(saved.name))
                    }
                send(Request(item, false, positionMs, media, attempt = 1))
            }
    }

    /** Stops serving the stream of the last saved file and closes it. */
    private fun closeStream() {
        streamFolder?.let { files?.unshare(it) }
        streamFolder = null
        stream?.let { closeLater(it) }
        stream = null
    }

    /**
     * Closes [old] on a thread of its own: it may be making a piece, and the player's coroutines are already
     * cancelled when it closes.
     */
    private fun closeLater(old: CastStream) {
        Thread({ old.close() }, "CastStream-close").start()
    }

    private fun noWifi(file: File) {
        AppLog.w(TAG, "${file.name} is saved, but the phone has no Wi-Fi address to serve it from")
        onStatus(activity.getString(R.string.cast_no_wifi), false)
    }

    /** Where the TV is in the film or episode, as position and length in ms; null when nothing is playing there. */
    fun tvPosition(): Pair<Long, Long>? {
        val client = session?.takeIf { it.isConnected }?.remoteMediaClient ?: return null
        val length = client.streamDuration
        return if (length > 0) client.approximateStreamPosition to length else null
    }

    /** One load sent to a Chromecast, kept to send again as it was. */
    private data class Request(
        val item: ItemEntity,
        val live: Boolean,
        val positionMs: Long,
        val media: CastMedia.Media,
        val attempt: Int,
    )

    private fun send(r: Request) {
        val s = session ?: return AppLog.w(TAG, "Not connected to a TV, so nothing was sent")
        val client = s.remoteMediaClient ?: return AppLog.w(TAG, "${name(s)} isn't ready for media yet")
        AppLog.i(TAG) {
            "Sending to ${name(s)}, try ${r.attempt}: ${r.media.url} as ${r.media.mimeType}, " +
                if (r.live) "live" else "from ${r.positionMs / MS_PER_S} s"
        }
        val metadata =
            MediaMetadata(if (r.live) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, r.item.name)
                putString(MediaMetadata.KEY_SUBTITLE, r.item.groupName)
                r.item.logo?.let { addImage(WebImage(it.toUri())) }
            }
        val text = subtitles.trackFor(r.item)
        val info =
            MediaInfo
                .Builder(r.media.url)
                .setStreamType(if (r.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(r.media.mimeType)
                .setMetadata(metadata)
                .setMediaTracks(listOfNotNull(text))
                .apply {
                    // The receiver reads HLS pieces as MPEG-TS unless told they are fragmented MP4.
                    if (r.media.fmp4) {
                        setHlsSegmentFormat(HlsSegmentFormat.FMP4)
                        setHlsVideoSegmentFormat(HlsVideoSegmentFormat.FMP4)
                    }
                }.build()
        client
            .load(
                MediaLoadRequestData
                    .Builder()
                    .setMediaInfo(info)
                    .setAutoplay(true)
                    .setCurrentTime(if (r.live) 0 else r.positionMs.coerceAtLeast(0))
                    .setActiveTrackIds(subtitles.activeIds())
                    .build(),
            ).setResultCallback { result -> loaded(result.status.isSuccess, result.status.statusCode, s, r) }
    }

    private fun loaded(
        ok: Boolean,
        status: Int,
        s: CastSession,
        r: Request,
    ) {
        if (ok) return AppLog.i(TAG) { "${name(s)} took it" }
        // A newer load took its place: not a failure, and trying again would undo the newer one.
        if (status == CastStatusCodes.REPLACED) return AppLog.i(TAG) { "A newer load replaced try ${r.attempt}" }
        AppLog.w(TAG, "${name(s)} couldn't load it, try ${r.attempt}: ${code(status)}")
        if (session !== s) return
        val wait = CastMedia.retryDelayMs(r.attempt)
        if (wait == null) {
            onStatus(activity.getString(R.string.cast_failed, name(s)), false)
            return
        }
        retry =
            activity.lifecycleScope.launchSafely(TAG) {
                delay(wait)
                if (session === s) send(r.copy(attempt = r.attempt + 1))
            }
    }

    /** The address a TV on the same Wi-Fi can fetch [file] from, or null when the phone has no local address. */
    private fun serve(file: File): String? {
        val host = localAddress() ?: return null
        val server = server() ?: return null
        return "http://$host:${server.port}${server.share(file)}"
    }

    /** The address of [stream]'s folder (ending in `/`) that a TV on the same Wi-Fi reads it from. */
    private fun serve(
        name: String,
        stream: FileServer.Made,
    ): String? {
        val host = localAddress() ?: return null
        val server = server() ?: return null
        return "http://$host:${server.port}${server.share(name, stream)}"
    }

    private fun server(): FileServer? = files
        ?: attempt(TAG, "Couldn't start sharing saved files") { FileServer { line -> AppLog.d(TAG) { line } } }
            .getOrNull()
            ?.also { files = it }

    /** The phone's address on the local network (Wi-Fi first), which the TV can reach. */
    private fun localAddress(): String? = attempt(TAG, "Couldn't read the phone's network address") {
        NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun stopServing() {
        closeStream()
        files?.close()
        files = null
    }

    /** Play on the TV; a video that failed or was stopped there is sent again from where it was. */
    fun play() {
        if (dlna.active) {
            dlnaPaused = false
            dlna.play()
            onTvChanged()
            return
        }
        val client = session?.remoteMediaClient ?: return
        val status = client.mediaStatus
        if (status == null || status.playerState == MediaStatus.PLAYER_STATE_IDLE) {
            val item = current() ?: return
            val at = if (status?.idleReason == MediaStatus.IDLE_REASON_FINISHED) 0L else client.approximateStreamPosition
            lastLoad = null
            load(item, isLive(), at)
        } else {
            client.play()
        }
    }

    fun pause() {
        if (dlna.active) {
            dlnaPaused = true
            dlna.pause()
            onTvChanged()
        } else {
            session?.remoteMediaClient?.pause()
        }
    }

    /**
     * Where the TV is in [item], for sending it again with its subtitles (picked with subtitle search while casting);
     * 0 when the TV is on something else.
     */
    fun positionOnTv(item: ItemEntity): Long {
        if (dlna.active || lastLoad?.first != item.id) return 0
        return session?.remoteMediaClient?.approximateStreamPosition ?: 0
    }

    /**
     * The player screen is going. When it closes for good ([leaving]), the cast ends with it: the TV stops and its app
     * closes, so the next show plays on the phone (Melvin, 2026-11-19). Going to the background or turning the phone
     * leaves the TV playing.
     */
    fun release(leaving: Boolean) {
        retry?.cancel()
        preparing?.cancel()
        stopServing()
        detach()
        // Before the cast ends, so its end doesn't start the phone's player as the screen closes.
        context?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        if (leaving && casting) {
            AppLog.i(TAG) { "The player closed: ending the cast on ${tvName ?: "the TV"}" }
            if (session?.isConnected == true) context?.sessionManager?.endCurrentSession(true)
            dlna.end()
        }
        dlna.release()
    }

    private companion object {
        const val TAG = "Cast"
        const val SAME_LOAD_MS = 2_000L
        const val MS_PER_S = 1_000

        /** The path of an address on the phone's server ("http://host:port/key/x/" is "/key/x/"), which it unshares. */
        fun pathOf(url: String): String = "/" + url.substringAfter("://").substringAfter('/')

        fun name(s: CastSession): String = s.castDevice?.friendlyName ?: "the TV"

        fun describe(s: CastSession): String = "${name(s)} (${s.castDevice?.modelName ?: "unknown model"})"

        fun code(status: Int): String = "${CastStatusCodes.getStatusCodeString(status)} ($status)"

        fun playerState(state: Int): String = when (state) {
            MediaStatus.PLAYER_STATE_IDLE -> "idle"
            MediaStatus.PLAYER_STATE_BUFFERING -> "buffering"
            MediaStatus.PLAYER_STATE_LOADING -> "loading"
            MediaStatus.PLAYER_STATE_PLAYING -> "playing"
            MediaStatus.PLAYER_STATE_PAUSED -> "paused"
            else -> "in an unknown state"
        }
    }
}

/** Whether a cast found on opening the player carries on. */
internal object CastResume {
    /** The TV is playing, paused, loading or buffering a video; idle or no media at all means it was stopped. */
    fun carriesOn(playerState: Int?): Boolean = playerState != null &&
        playerState != MediaStatus.PLAYER_STATE_IDLE &&
        playerState != MediaStatus.PLAYER_STATE_UNKNOWN
}

/**
 * The cast button, made so that it can never take the player down: if the device picker can't open (Play services
 * missing or updating, the screen going away mid-tap), the tap does nothing instead of crashing.
 */
private class SafeRouteButton(
    context: Context,
) : MediaRouteButton(context) {
    override fun showDialog(): Boolean = attempt("Cast", "Couldn't open the cast picker") { super.showDialog() }.getOrDefault(false)
}

/** Read by the Cast framework (manifest meta-data): the default receiver, with a notification while casting. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions
        .Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        .setCastMediaOptions(
            CastMediaOptions
                .Builder()
                .setNotificationOptions(NotificationOptions.Builder().setTargetActivityClassName(MainActivity::class.java.name).build())
                .build(),
        ).build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
