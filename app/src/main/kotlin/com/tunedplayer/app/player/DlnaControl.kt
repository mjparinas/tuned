package com.tunedplayer.app.player

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.mediarouter.media.MediaRouter
import com.tunedplayer.app.container
import com.tunedplayer.app.core.Dlna
import com.tunedplayer.app.core.StreamLink
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.diagnostics.loggingHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Playing on a DLNA TV picked from the cast picker. Like a Chromecast, the TV fetches the stream itself, so the phone
 * holds no connection of its own while it plays there.
 */
class DlnaControl(
    context: Context,
    private val owner: LifecycleOwner,
    /** The TV took over; the argument is its name. */
    private val onStarted: (String) -> Unit,
    /** The TV said yes to Play. */
    private val onPlaying: () -> Unit,
    /** The TV was let go of from the picker. */
    private val onEnded: () -> Unit,
) {
    private val http = context.container.http
    private val appScope = context.container.appScope
    private val router = MediaRouter.getInstance(context)

    /** Commands go one at a time and in order, so a quick change of channel can't overtake itself. */
    private val sending = Mutex()
    private var controlUrl: String? = null

    val active get() = controlUrl != null

    private val callback =
        object : MediaRouter.Callback() {
            override fun onRouteSelected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) {
                if (route.isDlna()) take(route)
            }

            override fun onRouteUnselected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) {
                if (route.isDlna() && active) {
                    send(Dlna.stop(), "Stop")
                    controlUrl = null
                    onEnded()
                }
            }
        }

    init {
        DlnaRouteProvider.register(context)
        // Discovery while the player is open, so the cast button shows when there is a TV to pick.
        router.addCallback(DlnaRouteProvider.selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
    }

    /** Opening the player while a TV is already playing carries on there. */
    fun resume() {
        router.selectedRoute.takeIf { it.isDlna() }?.let(::take)
    }

    private fun take(route: MediaRouter.RouteInfo) {
        controlUrl = route.extras?.getString(DlnaRouteProvider.EXTRA_CONTROL)
        if (controlUrl == null) return AppLog.w("Dlna", "${route.name} has no control address, so it can't be cast to")
        AppLog.i("Dlna") { "Casting to ${route.name}" }
        onStarted(route.name)
    }

    /** Plays [item] on the TV, from [positionMs] for films and episodes when the TV allows it. */
    fun load(
        item: ItemEntity,
        live: Boolean,
        positionMs: Long,
        /** The phone's own address for a saved copy, played instead of the stream. */
        savedUrl: String? = null,
    ) {
        send(Dlna.stop(), "Stop")
        val url = savedUrl ?: StreamLink.address(item.url) // a TV takes no extra headers
        AppLog.i("Dlna") { "Sending to the TV: $url (${Dlna.mimeFor(url)}), live=$live, from ${positionMs / 1000} s" }
        send(Dlna.setUri(url, item.name, Dlna.mimeFor(url)), "SetAVTransportURI")
        send(Dlna.play(), "Play", onDone = onPlaying)
        if (!live && positionMs > 0) send(Dlna.seek(positionMs), "Seek")
    }

    fun play() = send(Dlna.play(), "Play")

    /**
     * Stop casting from the casting screen: the picker lets go of the TV, which stops it and ends the cast as the
     * picker's own Stop casting does, so the phone carries on.
     */
    fun disconnect() {
        if (!active) return
        attempt("Dlna", "Couldn't let go of the TV") { router.unselect(MediaRouter.UNSELECT_REASON_STOPPED) }
    }

    fun pause() = send(Dlna.pause(), "Pause")

    /**
     * The player screen closed: the TV stops and the picker lets go of it, so the next show plays on the phone. Stop
     * is sent from the app's scope, since the screen's own is cancelled as it closes.
     */
    fun end() {
        if (!active) return
        router.removeCallback(callback)
        send(Dlna.stop(), "Stop", scope = appScope)
        controlUrl = null
        attempt("Dlna", "Couldn't let go of the TV") { router.unselect(MediaRouter.UNSELECT_REASON_STOPPED) }
    }

    private fun send(
        body: String,
        action: String,
        /** Run on the main thread when the TV accepts the command. */
        onDone: (() -> Unit)? = null,
        scope: CoroutineScope = owner.lifecycleScope,
    ) {
        val url = controlUrl ?: return
        scope.launch(Dispatchers.IO + loggingHandler("Dlna")) {
            sending.withLock {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("SOAPAction", Dlna.soapAction(action))
                        .post(body.toRequestBody(SOAP))
                        .build()
                // A TV that refuses one step (Stop when idle, Seek on a live stream) still plays.
                attempt("Dlna", "The TV refused $action") {
                    http.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            AppLog.i("Dlna") { "$action: ${response.code}" }
                            onDone?.let { done -> owner.lifecycleScope.launchSafely("Dlna") { done() } }
                        } else {
                            AppLog.w("Dlna", "The TV answered $action with ${response.code} ${response.message}")
                        }
                    }
                }
            }
        }
    }

    fun release() {
        router.removeCallback(callback)
    }

    private fun MediaRouter.RouteInfo.isDlna() = supportsControlCategory(DlnaRouteProvider.CATEGORY)

    private companion object {
        val SOAP = "text/xml; charset=\"utf-8\"".toMediaType()
    }
}
