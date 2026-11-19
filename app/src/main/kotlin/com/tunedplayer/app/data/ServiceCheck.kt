package com.tunedplayer.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tunedplayer.app.core.ScanEngine
import com.tunedplayer.app.core.XtreamAccount
import com.tunedplayer.app.core.XtreamUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Is it the channel, the internet or the service? Used by the channel scan and by the player before it blames a
 * channel. Neither check opens a stream: the account call is a small answer from `player_api.php`.
 */
class ServiceCheck(
    private val context: Context,
    private val client: OkHttpClient,
    private val credentials: CredentialStore,
) {
    /** Android's own check of the network: connected and validated (it reached the internet). */
    fun internetUp(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** UP when the login is accepted with a connection free; UNKNOWN for a plain M3U, which has no account call. */
    suspend fun server(userAgent: String): ScanEngine.Server {
        val login = credentials.load() ?: return ScanEngine.Server.DOWN
        if (login.playlistUrl.isNotBlank()) return ScanEngine.Server.UNKNOWN
        val request =
            Request
                .Builder()
                .url(XtreamUrls.account(login.server, login.username, login.password))
                .header("User-Agent", userAgent)
                .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { r ->
                    val body = r.body.string().take(MAX_ACCOUNT_ANSWER)
                    when {
                        !r.isSuccessful || XtreamAccount.accepted(body) == false -> ScanEngine.Server.DOWN
                        XtreamAccount.connectionsFull(body) == true -> ScanEngine.Server.BUSY
                        else -> ScanEngine.Server.UP
                    }
                }
            } catch (_: IOException) {
                ScanEngine.Server.DOWN
            }
        }
    }

    private companion object {
        const val MAX_ACCOUNT_ANSWER = 64 * 1024
    }
}
