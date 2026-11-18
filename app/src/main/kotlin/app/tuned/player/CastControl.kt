package app.tuned.player

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouteSelector
import app.tuned.R
import app.tuned.core.CastMedia
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

/**
 * Chromecast from the player. When a cast starts, the phone stops its own stream (so only one connection to the
 * provider is ever open) and the TV plays the channel; changing channel while casting changes it on the TV, and ending
 * the cast picks the stream back up on the phone. DLNA TVs show in the same picker ([DlnaControl]); phones without
 * Google Play services list only those.
 *
 * Every step goes to the log (Settings › Developer › Logs, logins masked): the session, the address and type sent, the
 * TV's answer and any error it reports. A load the TV turns down is tried again after a pause ([CastMedia.retryDelayMs]),
 * since a provider can still count the phone's connection for a moment after it closes; if every try fails, the note
 * over the video says so.
 */
class CastControl(
    private val activity: FragmentActivity,
    private val player: () -> ExoPlayer?,
    private val current: () -> ItemEntity?,
    private val isLive: () -> Boolean,
    /** Shows a lasting note over the video ("Playing on Living Room TV"), or hides it with null. */
    private val onStatus: (String?) -> Unit,
) {
    @Suppress("DEPRECATION") // the Task-based replacement needs an executor and a callback for the same result
    private val context: CastContext? =
        attempt("Cast", "Cast isn't available on this device") { CastContext.getSharedInstance(activity) }.getOrNull()
    private var session: CastSession? = null
    private var lastPositionMs = 0L
    private var retry: Job? = null
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
        session?.remoteMediaClient?.unregisterCallback(mediaCallback)
        session = null
        onStatus(null)
        val exo = player() ?: return
        if (!isLive() && lastPositionMs > 0) exo.seekTo(lastPositionMs)
        exo.prepare()
        exo.play()
    }

    /** Plays [item] on the TV, from [positionMs] for films and episodes. */
    fun load(
        item: ItemEntity,
        live: Boolean,
        positionMs: Long,
    ) {
        if (dlna.active) return dlna.load(item, live, positionMs)
        retry?.cancel()
        send(item, live, positionMs, attempt = 1)
    }

    private fun send(
        item: ItemEntity,
        live: Boolean,
        positionMs: Long,
        attempt: Int,
    ) {
        val s = session ?: return AppLog.w(TAG, "Not connected to a TV, so nothing was sent")
        val client = s.remoteMediaClient ?: return AppLog.w(TAG, "${name(s)} isn't ready for media yet")
        val media = CastMedia.forCast(StreamLink.address(item.url), live) // a Chromecast takes no extra headers
        AppLog.i(TAG) {
            "Sending to ${name(s)}, try $attempt: ${media.url} as ${media.mimeType}, " +
                if (live) "live" else "from ${positionMs / MS_PER_S} s"
        }
        val metadata =
            MediaMetadata(if (live) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, item.name)
                putString(MediaMetadata.KEY_SUBTITLE, item.groupName)
                item.logo?.let { addImage(WebImage(it.toUri())) }
            }
        val info =
            MediaInfo
                .Builder(media.url)
                .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(media.mimeType)
                .setMetadata(metadata)
                .build()
        client
            .load(
                MediaLoadRequestData
                    .Builder()
                    .setMediaInfo(info)
                    .setAutoplay(true)
                    .setCurrentTime(if (live) 0 else positionMs.coerceAtLeast(0))
                    .build(),
            ).setResultCallback { result -> loaded(result.status.isSuccess, result.status.statusCode, s, item, live, positionMs, attempt) }
    }

    @Suppress("LongParameterList") // the one load being answered, to try again as it was
    private fun loaded(
        ok: Boolean,
        status: Int,
        s: CastSession,
        item: ItemEntity,
        live: Boolean,
        positionMs: Long,
        attempt: Int,
    ) {
        if (ok) return AppLog.i(TAG) { "${name(s)} took it" }
        AppLog.w(TAG, "${name(s)} couldn't load it, try $attempt: ${code(status)}")
        if (session !== s) return
        val wait = CastMedia.retryDelayMs(attempt)
        if (wait == null) {
            onStatus(activity.getString(R.string.cast_failed, name(s)))
            return
        }
        retry =
            activity.lifecycleScope.launchSafely(TAG) {
                delay(wait)
                if (session === s) send(item, live, positionMs, attempt + 1)
            }
    }

    fun play() {
        if (dlna.active) dlna.play() else session?.remoteMediaClient?.play()
    }

    fun pause() {
        if (dlna.active) dlna.pause() else session?.remoteMediaClient?.pause()
    }

    fun release() {
        retry?.cancel()
        session?.remoteMediaClient?.unregisterCallback(mediaCallback)
        context?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        dlna.release()
    }

    private companion object {
        const val TAG = "Cast"
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
