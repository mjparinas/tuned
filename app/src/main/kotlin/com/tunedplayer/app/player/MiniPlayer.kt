package com.tunedplayer.app.player

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.tunedplayer.app.AppContainer
import com.tunedplayer.app.container
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The mini player (Melvin, 2026-11-21: "when i'm streaming something on live tv, and then click back, i want the video
 * to continue playing in a small video somewhere out of the way ... when i find something i like and click on it, it
 * should stop the mini live tv from streaming first before it streams the new show"; docs/decisions/player.md).
 *
 * Back on a live channel shrinks the player into Android's picture-in-picture window, in a corner over the app, and
 * the app's lists show underneath to browse. It is the same player screen, the same player and the same connection,
 * so nothing reconnects, and downloads keep waiting as they do while anything plays. Tapping the window (or, on a TV,
 * the system's picture-in-picture menu) brings it back to full screen, and so does picking the same channel again.
 *
 * One stream, ever: anything else picked while the window shows (a channel, a film, an episode, catch-up, a recording
 * or an event to tune) closes the window first, waits until its stream has hung up, and only then opens ([open]).
 * Back on the app's first screen closes the window too ([closeOnBack]), which is a remote's way to stop it on a TV,
 * where the window never takes the remote's focus.
 */
object MiniPlayer {
    private const val TAG = "MiniPlayer"

    /** How long a closing player may take to hang up before the next pick opens anyway (the player then restarts on it). */
    const val HANG_UP_MS = 3_000L

    private val shown = MutableStateFlow<PlayRequest?>(null)

    /** What the mini player is playing, or null when there is none. */
    internal val showing: StateFlow<PlayRequest?> = shown

    @Volatile
    private var closer: (() -> Unit)? = null

    /**
     * Between closing the mini player for something new and that opening: the closing player doesn't let downloads
     * carry on, since the next stream is about to start.
     */
    @Volatile
    var handingOver = false
        private set

    /** The player screen went into the small window, playing [request]; [close] ends it. */
    internal fun shown(
        request: PlayRequest,
        close: () -> Unit,
    ) {
        closer = close
        shown.value = request
    }

    /** The player screen that called [shown] with [close] is full screen again, or gone. */
    internal fun gone(close: () -> Unit) {
        if (closer !== close) return
        closer = null
        shown.value = null
    }

    /**
     * The player screen hung up its stream: downloads carry on, unless it closed for the next pick from the mini player,
     * which opens now, so they keep waiting.
     */
    internal fun playerClosed(container: AppContainer) {
        container.playerOpen.value = false
        if (!handingOver) container.appScope.launch { container.offline.start() }
    }

    /** Closes the mini player, which hangs up its stream. False when there was none. */
    fun close(): Boolean {
        val close = closer ?: return false
        AppLog.i(TAG) { "Closing the mini player" }
        attempt(TAG, "Couldn't close the mini player") { close() }
        return true
    }

    /**
     * Opens a player [intent] from the app. With the mini player showing something else, that closes first and the
     * pick opens once its stream has hung up; the same pick brings the window back to full screen.
     */
    fun open(
        context: Context,
        intent: Intent,
    ) {
        val now = shown.value
        val close = closer
        if (now == null || close == null || now == PlayRequest.of(intent)) {
            context.startActivity(intent)
            return
        }
        val container = context.container
        // Nothing opened after all: downloads carry on rather than wait for a player that never comes.
        val failed: (Throwable) -> Unit = { container.appScope.launch { container.offline.start() } }
        container.appScope.launchSafely(TAG, onError = failed) {
            handOver(close, container.playerOpen) { withContext(Dispatchers.Main) { context.startActivity(intent) } }
        }
    }

    /**
     * Closes the mini player with [close], waits until [playerOpen] says its stream hung up (at most [HANG_UP_MS]), then
     * calls [start]. The provider never sees the old stream and the new one at once.
     */
    internal suspend fun handOver(
        close: () -> Unit,
        playerOpen: StateFlow<Boolean>,
        start: suspend () -> Unit,
    ) {
        handingOver = true
        try {
            AppLog.i(TAG) { "Something new picked: the mini player hangs up first" }
            withContext(Dispatchers.Main.immediate) { close() }
            val hungUp = withTimeoutOrNull(HANG_UP_MS) { playerOpen.first { !it } } != null
            if (!hungUp) AppLog.w(TAG, "The mini player took over ${HANG_UP_MS / 1_000} s to close; the player restarts on the pick")
            start()
        } finally {
            handingOver = false
        }
    }

    /**
     * Whether Back should shrink the player into the mini player rather than close it: the setting is on, the device
     * has picture-in-picture, a live channel (not catch-up or a recording) is playing on this device (not cast), and
     * the app's lists are open to browse under it (not opened straight from a widget or another app).
     */
    internal fun shrinksOnBack(
        enabled: Boolean,
        supported: Boolean,
        liveChannel: Boolean,
        casting: Boolean,
        playing: Boolean,
        appOpen: Boolean,
    ): Boolean = enabled && supported && liveChannel && !casting && playing && appOpen

    /** Back on the app's own screens, once nothing else wants it, closes the mini player before it leaves the app. */
    fun closeOnBack(activity: ComponentActivity) {
        val callback =
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    if (!close()) isEnabled = false
                }
            }
        // Added before the screens' own, so they come first: this only takes Back that would leave the app.
        activity.onBackPressedDispatcher.addCallback(activity, callback)
        activity.lifecycleScope.launchSafely(TAG) { showing.collect { callback.isEnabled = it != null } }
    }

    /**
     * The player screen's side of it: Back, entering and leaving the small window, and closing. It wires itself to
     * [activity] when created, so the player only creates it (in onCreate, before the tools' own Back, so the guide and
     * the lock still come first).
     */
    class Screen(
        private val activity: ComponentActivity,
        private val settings: () -> AppSettings,
        /** A live channel's queue (catch-up and recordings are told apart here). */
        private val live: () -> Boolean,
        private val casting: () -> Boolean,
        private val player: () -> Player?,
        private val view: () -> PlayerView,
    ) {
        private val close: () -> Unit = { activity.finish() }

        /** Back: the mini player when it can, otherwise what Back always did (close the player). */
        private val back =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (shrink()) return
                    isEnabled = false
                    activity.onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }

        init {
            activity.onBackPressedDispatcher.addCallback(activity, back)
            activity.addOnPictureInPictureModeChangedListener { change ->
                if (change.isInPictureInPictureMode) shown(PlayRequest.of(activity.intent), close) else gone(close)
            }
            activity.lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) gone(close) })
        }

        private fun shrink(): Boolean {
            val supported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            val go =
                shrinksOnBack(
                    enabled = settings().miniPlayer,
                    supported = supported,
                    liveChannel = live() && player()?.currentMediaItem?.mediaId != CatchUpLinks.ID.toString(),
                    casting = casting(),
                    playing = player()?.playWhenReady == true,
                    appOpen = appOpen(activity),
                )
            if (!go) return false
            // Android refuses when picture-in-picture is turned off for Tuned in its settings: Back closes the player then.
            val entered =
                attempt(TAG, "Couldn't shrink into the mini player") {
                    activity.enterPictureInPictureMode(pipParams(player(), view(), settings().pictureInPicture))
                }
            return entered.getOrDefault(false)
        }

        /** The app's lists are open in a task of their own or under the player. */
        private fun appOpen(activity: Activity): Boolean {
            val tasks = attempt(TAG, "Couldn't read the app's screens") { activity.getSystemService(ActivityManager::class.java)?.appTasks }
            return tasks.getOrNull().orEmpty().any { task ->
                val base = attempt(TAG, "Couldn't read a screen") { task.taskInfo?.baseActivity?.className }
                base.getOrNull() == MainActivity::class.java.name
            }
        }
    }
}
