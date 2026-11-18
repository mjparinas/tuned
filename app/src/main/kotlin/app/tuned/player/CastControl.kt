package app.tuned.player

import android.content.Context
import android.os.SystemClock
import android.view.ContextThemeWrapper
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouteSelector
import app.tuned.R
import app.tuned.core.CastMedia
import app.tuned.core.FileServer
import app.tuned.core.StreamLink
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.MainActivity
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** Shows a lasting note over the video ("Playing on Living Room TV"), or hides it with null. */
    private val onStatus: (String?) -> Unit,
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
    private val copies = CastCopy(File(activity.cacheDir, "cast"))
    private var lastPlayerState = MediaStatus.PLAYER_STATE_UNKNOWN

    /** What the TV says about the media: logged, so a cast that shows "No media selected" says why. */
    private val mediaCallback =
        object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val status = session?.remoteMediaClient?.mediaStatus ?: return
                if (status.playerState == lastPlayerState) return
                lastPlayerState = status.playerState
                if (status.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                    AppLog.w(TAG, "The TV stopped with an error")
                } else {
                    AppLog.i(TAG) { "TV is ${playerState(status.playerState)}" }
                }
            }

            override fun onMediaError(error: MediaError) {
                AppLog.w(TAG, "The TV reported a media error: ${error.type}, reason ${error.reason}, code ${error.detailedErrorCode}")
            }
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

    private val dlna = DlnaControl(activity, activity, onStarted = ::handOff, onEnded = ::ended)

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
                lastPositionMs = s.remoteMediaClient?.approximateStreamPosition ?: 0L
            }

            override fun onSessionEnded(
                s: CastSession,
                error: Int,
            ) {
                AppLog.i(TAG) { "Cast ended: ${code(error)}" }
                ended()
            }

            override fun onSessionStartFailed(
                s: CastSession,
                error: Int,
            ) = AppLog.w(TAG, "Couldn't connect to ${name(s)}: ${code(error)}")

            override fun onSessionResumeFailed(
                s: CastSession,
                error: Int,
            ) = AppLog.w(TAG, "Couldn't reconnect to ${name(s)}: ${code(error)}")

            override fun onSessionStarting(s: CastSession) = AppLog.i(TAG) { "Connecting to ${describe(s)}" }

            override fun onSessionResuming(
                s: CastSession,
                sessionId: String,
            ) = AppLog.i(TAG) { "Reconnecting to ${describe(s)}" }

            override fun onSessionSuspended(
                s: CastSession,
                reason: Int,
            ) = AppLog.w(TAG, "Connection to ${name(s)} paused (reason $reason)")
        }

    init {
        context?.sessionManager?.addSessionManagerListener(listener, CastSession::class.java)
        // Opening the player while already casting carries on casting.
        context
            ?.sessionManager
            ?.currentCastSession
            ?.takeIf { it.isConnected }
            ?.let { attach(it) }
        dlna.resume()
    }

    /** The TV takes over: the phone lets go of its stream so the provider sees a single connection. */
    private fun started(s: CastSession) {
        AppLog.i(TAG) { "Connected to ${describe(s)}" }
        attach(s)
        handOff(name(s))
    }

    private fun attach(s: CastSession) {
        session?.remoteMediaClient?.unregisterCallback(mediaCallback)
        session = s
        lastPlayerState = MediaStatus.PLAYER_STATE_UNKNOWN
        s.remoteMediaClient?.registerCallback(mediaCallback)
    }

    /**
     * The phone lets go of its stream before the TV opens its own, so the provider never sees two connections at once
     * (an account allowed one turns the second away, and the TV is left with nothing to play).
     */
    private fun handOff(name: String) {
        lastPositionMs = 0L
        onStatus(activity.getString(R.string.cast_playing_on, name))
        val exo = player() ?: return AppLog.w(TAG, "No player to hand over to the TV")
        val item = current() ?: return AppLog.w(TAG, "Nothing playing to send to the TV")
        val positionMs = exo.currentPosition
        exo.stop()
        load(item, isLive(), positionMs)
    }

    /** Back on the phone, from where the TV was for films and episodes. */
    private fun ended() {
        retry?.cancel()
        preparing?.cancel()
        lastLoad = null
        stopServing()
        session?.remoteMediaClient?.unregisterCallback(mediaCallback)
        session = null
        onStatus(null)
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
        val from = positionMs.coerceAtLeast(0)
        // A saved film or episode plays from the phone, never the provider ("saved before streamed").
        val saved = savedFile(item.id)
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
     * A Chromecast gets a saved file as an MP4 it can play ([CastCopy]), made on the phone first when needed, with
     * the progress over the video. DLNA TVs take MKV and Dolby sound, so they get the file as it is.
     */
    private fun sendSaved(
        item: ItemEntity,
        saved: File,
        positionMs: Long,
    ) {
        val s = session ?: return AppLog.w(TAG, "Not connected to a TV, so nothing was sent")
        preparing =
            activity.lifecycleScope.launchSafely(TAG) {
                // The copy reports from its own thread; the note is a view.
                val file =
                    copies.forCast(saved) { percent ->
                        activity.runOnUiThread { onStatus(activity.getString(R.string.cast_preparing, name(s), percent)) }
                    }
                if (session !== s) return@launchSafely
                onStatus(activity.getString(R.string.cast_playing_on, name(s)))
                val url = serve(file) ?: return@launchSafely noWifi(file)
                send(Request(item, false, positionMs, CastMedia.Media(url, CastMedia.mimeFor(file.name)), attempt = 1))
            }
    }

    private fun noWifi(file: File) {
        AppLog.w(TAG, "${file.name} is saved, but the phone has no Wi-Fi address to serve it from")
        onStatus(activity.getString(R.string.cast_no_wifi))
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
        val info =
            MediaInfo
                .Builder(r.media.url)
                .setStreamType(if (r.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(r.media.mimeType)
                .setMetadata(metadata)
                .build()
        client
            .load(
                MediaLoadRequestData
                    .Builder()
                    .setMediaInfo(info)
                    .setAutoplay(true)
                    .setCurrentTime(if (r.live) 0 else r.positionMs.coerceAtLeast(0))
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
            onStatus(activity.getString(R.string.cast_failed, name(s)))
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
        val server =
            files ?: attempt(TAG, "Couldn't start sharing saved files") { FileServer { line -> AppLog.d(TAG) { line } } }
                .getOrNull()
                ?.also { files = it }
                ?: return null
        return "http://$host:${server.port}${server.share(file)}"
    }

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
        files?.close()
        files = null
    }

    fun play() {
        if (dlna.active) dlna.play() else session?.remoteMediaClient?.play()
    }

    fun pause() {
        if (dlna.active) dlna.pause() else session?.remoteMediaClient?.pause()
    }

    fun release() {
        retry?.cancel()
        preparing?.cancel()
        copies.clear()
        stopServing()
        session?.remoteMediaClient?.unregisterCallback(mediaCallback)
        context?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        dlna.release()
    }

    private companion object {
        const val TAG = "Cast"
        const val SAME_LOAD_MS = 2_000L
        const val MS_PER_S = 1_000

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
