package app.tuned.player

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.mediarouter.media.MediaRouter
import app.tuned.container
import app.tuned.core.Dlna
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.loggingHandler
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
    /** The TV was let go of from the picker. */
    private val onEnded: () -> Unit,
) {
    private val http = context.container.http
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
        controlUrl = route.extras?.getString(DlnaRouteProvider.EXTRA_CONTROL) ?: return
        onStarted(route.name)
    }

    /** Plays [item] on the TV, from [positionMs] for films and episodes when the TV allows it. */
    fun load(
        item: ItemEntity,
        live: Boolean,
        positionMs: Long,
    ) {
        send(Dlna.stop(), "Stop")
        send(Dlna.setUri(item.url, item.name, Dlna.mimeFor(item.url)), "SetAVTransportURI")
        send(Dlna.play(), "Play")
        if (!live && positionMs > 0) send(Dlna.seek(positionMs), "Seek")
    }

    fun play() = send(Dlna.play(), "Play")

    fun pause() = send(Dlna.pause(), "Pause")

    private fun send(
        body: String,
        action: String,
    ) {
        val url = controlUrl ?: return
        owner.lifecycleScope.launch(Dispatchers.IO + loggingHandler("Dlna")) {
            sending.withLock {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("SOAPAction", Dlna.soapAction(action))
                        .post(body.toRequestBody(SOAP))
                        .build()
                // A TV that refuses one step (Stop when idle, Seek on a live stream) still plays.
                attempt("Dlna", "The TV refused $action") { http.newCall(request).execute().close() }
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
