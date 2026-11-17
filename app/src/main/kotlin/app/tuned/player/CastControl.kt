package app.tuned.player

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.media3.exoplayer.ExoPlayer
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouteSelector
import app.tuned.R
import app.tuned.core.CastMedia
import app.tuned.core.StreamLink
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.attempt
import app.tuned.ui.MainActivity
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.common.images.WebImage

/**
 * Chromecast from the player. When a cast starts, the phone stops its own stream (so only one connection to the
 * provider is ever open) and the TV plays the channel; changing channel while casting changes it on the TV, and ending
 * the cast picks the stream back up on the phone. DLNA TVs show in the same picker ([DlnaControl]); phones without
 * Google Play services list only those.
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
            ) = ended()

            override fun onSessionStartFailed(
                s: CastSession,
                error: Int,
            ) = Unit

            override fun onSessionResumeFailed(
                s: CastSession,
                error: Int,
            ) = Unit

            override fun onSessionStarting(s: CastSession) = Unit

            override fun onSessionResuming(
                s: CastSession,
                sessionId: String,
            ) = Unit

            override fun onSessionSuspended(
                s: CastSession,
                reason: Int,
            ) = Unit
        }

    init {
        context?.sessionManager?.addSessionManagerListener(listener, CastSession::class.java)
        // Opening the player while already casting carries on casting.
        context
            ?.sessionManager
            ?.currentCastSession
            ?.takeIf { it.isConnected }
            ?.let { session = it }
        dlna.resume()
    }

    /** The TV takes over: the phone lets go of its stream so the provider sees a single connection. */
    private fun started(s: CastSession) {
        session = s
        handOff(s.castDevice?.friendlyName.orEmpty())
    }

    private fun handOff(name: String) {
        lastPositionMs = 0L
        onStatus(activity.getString(R.string.cast_playing_on, name))
        val exo = player() ?: return
        current()?.let { load(it, isLive(), exo.currentPosition) }
        exo.stop()
    }

    /** Back on the phone, from where the TV was for films and episodes. */
    private fun ended() {
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
        val client = session?.remoteMediaClient ?: return
        val media = CastMedia.forCast(StreamLink.address(item.url), live) // a Chromecast takes no extra headers
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
        client.load(
            MediaLoadRequestData
                .Builder()
                .setMediaInfo(info)
                .setAutoplay(true)
                .setCurrentTime(if (live) 0 else positionMs.coerceAtLeast(0))
                .build(),
        )
    }

    fun play() {
        if (dlna.active) dlna.play() else session?.remoteMediaClient?.play()
    }

    fun pause() {
        if (dlna.active) dlna.pause() else session?.remoteMediaClient?.pause()
    }

    fun release() {
        context?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        dlna.release()
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
