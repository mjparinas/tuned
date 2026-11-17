package app.tuned.player

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Xml
import androidx.mediarouter.media.MediaRouteDescriptor
import androidx.mediarouter.media.MediaRouteDiscoveryRequest
import androidx.mediarouter.media.MediaRouteProvider
import androidx.mediarouter.media.MediaRouteProviderDescriptor
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.tuned.container
import app.tuned.core.Dlna
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Puts DLNA (UPnP) TVs in the same cast picker as Chromecasts. While the picker or the player wants routes, it asks the
 * local network for media renderers every so often and publishes each one it can tell what to play.
 */
class DlnaRouteProvider(
    context: Context,
    private val http: OkHttpClient,
) : MediaRouteProvider(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val renderers = ConcurrentHashMap<String, Dlna.Renderer>()
    private var discovery: Job? = null

    override fun onDiscoveryRequestChanged(request: MediaRouteDiscoveryRequest?) {
        discovery?.cancel()
        discovery =
            if (request?.selector?.hasControlCategory(CATEGORY) == true) {
                scope.launch {
                    while (isActive) {
                        search()
                        publish()
                        delay(if (request.isActiveScan) ACTIVE_EVERY_MS else PASSIVE_EVERY_MS)
                    }
                }
            } else {
                null
            }
    }

    /** One search: send the question, collect answers for a few seconds, read each new renderer's description. */
    private fun search() {
        val lock = wifi?.createMulticastLock("tuned-dlna")?.apply { setReferenceCounted(false) }
        try {
            lock?.acquire()
            val locations = DatagramSocket().use(::ask)
            locations.forEach { location -> describe(location)?.let { renderers[it.udn] = it } }
        } catch (_: IOException) {
            // No Wi-Fi or the network refused multicast: nothing to list this time.
        } finally {
            lock?.release()
        }
    }

    /** Sends the search and returns the description links of the renderers that answer in time. */
    private fun ask(socket: DatagramSocket): Set<String> {
        socket.soTimeout = ANSWER_WAIT_MS
        val question = Dlna.search().toByteArray()
        socket.send(DatagramPacket(question, question.size, InetAddress.getByName(Dlna.SSDP_HOST), Dlna.SSDP_PORT))
        val locations = mutableSetOf<String>()
        val buffer = ByteArray(BUFFER)
        val until = System.currentTimeMillis() + ANSWER_WAIT_MS
        while (System.currentTimeMillis() < until) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                break
            }
            Dlna.location(String(packet.data, 0, packet.length))?.let(locations::add)
        }
        return locations
    }

    private fun describe(location: String): Dlna.Renderer? = try {
        http.newCall(Request.Builder().url(location).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val parser = Xml.newPullParser().apply { setInput(response.body.charStream()) }
            Dlna.renderer(parser, location)
        }
    } catch (_: IOException) {
        null
    } catch (_: org.xmlpull.v1.XmlPullParserException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private suspend fun publish() {
        val routes =
            renderers.values.map { r ->
                MediaRouteDescriptor
                    .Builder(r.udn, r.name)
                    .addControlFilter(IntentFilter().apply { addCategory(CATEGORY) })
                    .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                    .setDeviceType(MediaRouter.RouteInfo.DEVICE_TYPE_TV)
                    .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED)
                    .setExtras(Bundle().apply { putString(EXTRA_CONTROL, r.controlUrl) })
                    .build()
            }
        withContext(Dispatchers.Main) { descriptor = MediaRouteProviderDescriptor.Builder().addRoutes(routes).build() }
    }

    companion object {
        /** The control category every DLNA route carries, so the picker and the player can tell them apart. */
        const val CATEGORY = "app.tuned.category.DLNA"
        const val EXTRA_CONTROL = "app.tuned.extra.DLNA_CONTROL"

        val selector: MediaRouteSelector = MediaRouteSelector.Builder().addControlCategory(CATEGORY).build()

        private var registered = false

        /**
         * Adds the provider once for the whole app (main thread). It stays, so a TV that is still playing after the
         * player closes is still the selected route when the player opens again.
         */
        fun register(context: Context) {
            if (registered) return
            registered = true
            MediaRouter.getInstance(context).addProvider(DlnaRouteProvider(context.applicationContext, context.container.http))
        }

        private const val ANSWER_WAIT_MS = 3_000
        private const val ACTIVE_EVERY_MS = 10_000L
        private const val PASSIVE_EVERY_MS = 60_000L
        private const val BUFFER = 4_096
    }
}
