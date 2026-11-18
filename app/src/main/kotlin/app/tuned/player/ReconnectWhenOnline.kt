package app.tuned.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import app.tuned.diagnostics.attempt

/**
 * Settings › When a channel fails › reconnect: when the network comes back, [onOnline] runs (on Android's network
 * thread), so a stream that dropped with the connection can start again. Moved out of `PlayerActivity` so the
 * activity only starts and stops it.
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
