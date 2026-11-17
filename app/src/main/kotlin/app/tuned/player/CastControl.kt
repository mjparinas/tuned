package app.tuned.player

import android.content.Context
import android.net.Uri
import android.view.ContextThemeWrapper
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteButton
import app.tuned.core.CastMedia
import app.tuned.data.db.ItemEntity
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
 * the cast picks the stream back up on the phone. Phones without Google Play services simply get no cast button.
 */
class CastControl(
    activity: FragmentActivity,
    private val onStart: (device: String) -> Unit,
    private val onEnd: (positionMs: Long) -> Unit,
) {
    @Suppress("DEPRECATION") // the Task-based replacement needs an executor and a callback for the same result
    private val context: CastContext? = runCatching { CastContext.getSharedInstance(activity) }.getOrNull()
    private var session: CastSession? = null
    private var lastPositionMs = 0L

    /** The standard cast button, or null without Play services. */
    val button: MediaRouteButton? =
        context?.let {
            MediaRouteButton(ContextThemeWrapper(activity, androidx.mediarouter.R.style.Theme_MediaRouter)).also { button ->
                CastButtonFactory.setUpMediaRouteButton(activity.applicationContext, button)
            }
        }

    val casting get() = session?.isConnected == true

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
    }

    private fun started(s: CastSession) {
        session = s
        onStart(s.castDevice?.friendlyName.orEmpty())
    }

    private fun ended() {
        session = null
        onEnd(lastPositionMs)
    }

    /** Plays [item] on the TV, from [positionMs] for films and episodes. */
    fun load(
        item: ItemEntity,
        live: Boolean,
        positionMs: Long,
    ) {
        val client = session?.remoteMediaClient ?: return
        val media = CastMedia.forCast(item.url, live)
        val metadata =
            MediaMetadata(if (live) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, item.name)
                putString(MediaMetadata.KEY_SUBTITLE, item.groupName)
                item.logo?.let { addImage(WebImage(Uri.parse(it))) }
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
        session?.remoteMediaClient?.play()
    }

    fun pause() {
        session?.remoteMediaClient?.pause()
    }

    fun release() {
        context?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
    }
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
