package app.tuned.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import app.tuned.diagnostics.attempt

/**
 * When the connection comes back (Wi-Fi to mobile, a tunnel, a router restart), [onOnline] runs (on Android's network
 * thread), so a stream that stopped on an error starts again by itself, from where it was for films and episodes.
 * Moved out of `PlayerActivity` so the activity only starts and stops it.
 */
class ReconnectWhenOnline(
    private val context: Context,
    private val onOnline: () -> Unit,
) {
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onOnline()
            }
        attempt("Player", "Couldn't watch the network") { cm.registerDefaultNetworkCallback(cb) }.onSuccess { callback = cb }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        attempt("Player", "Couldn't stop watching the network") {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        }
    }
}
