package com.tunedplayer.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Whether the device can reach the internet, for the offline banner and for choosing what can play. Online means
 * Android's own check passed (a network that reached the internet), the same test the channel scan uses. Going
 * offline is reported after a short wait, so switching from Wi-Fi to mobile data doesn't flash the banner; coming
 * back is reported at once.
 */
class Connectivity(
    private val context: Context,
    scope: CoroutineScope,
) {
    val online: StateFlow<Boolean> =
        changes()
            .settled(OFFLINE_AFTER_MS)
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), true)

    /** Right now, without waiting: for a tap that has to decide straight away. */
    fun onlineNow(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.getNetworkCapabilities(cm.activeNetwork).reachesInternet()
    }

    private fun changes(): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(true)
            awaitClose()
            return@callbackFlow
        }
        trySend(onlineNow())
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities,
                ) {
                    trySend(caps.reachesInternet())
                }

                override fun onLost(network: Network) {
                    trySend(false)
                }
            }
        val registered = attempt(TAG, "Couldn't watch the network") { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) attempt(TAG, "Couldn't stop watching the network") { cm.unregisterNetworkCallback(callback) } }
    }

    private fun NetworkCapabilities?.reachesInternet(): Boolean = this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    companion object {
        private const val TAG = "Connectivity"
        const val OFFLINE_AFTER_MS = 3_000L
        private const val STOP_AFTER_MS = 5_000L
    }
}

/**
 * Passes the first answer and every `true` on at once, and a later `false` only once it has held for
 * [offlineAfterMs], so a moment between two networks isn't reported as offline.
 */
fun Flow<Boolean>.settled(offlineAfterMs: Long): Flow<Boolean> = channelFlow {
    var started = false
    var pending: Job? = null
    distinctUntilChanged().collect { up ->
        pending?.cancel()
        if (up || !started) {
            send(up)
        } else {
            pending =
                launch {
                    delay(offlineAfterMs)
                    send(false)
                }
        }
        started = true
    }
}.distinctUntilChanged()
