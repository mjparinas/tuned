package com.tunedplayer.app.data

import com.tunedplayer.app.core.MediaServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * The signed-in Plex, Jellyfin or Emby server for this session, and the step that turns a stored `plex://` /
 * `jellyfin://` / `emby://` item into the stream to open. Every other link is handed back as it is.
 */
class MediaServerLinks(
    private val http: OkHttpClient,
    private val login: () -> Credentials?,
) {
    @Volatile private var emby: EmbyServer? = null

    @Volatile private var plex: PlexServer? = null

    fun emby(credentials: Credentials): EmbyServer {
        val product = MediaServer.Product.ofMarker(credentials.playlistUrl) ?: MediaServer.Product.Jellyfin
        emby
            ?.takeIf {
                it.server == credentials.server && it.product == product
            }?.let { return it }
        return EmbyServer(http, credentials.server, credentials.username, credentials.password, product).also { emby = it }
    }

    fun plex(credentials: Credentials): PlexServer {
        plex?.takeIf { it.server == credentials.server }?.let { return it }
        return PlexServer(http, credentials.server, credentials.password).also { plex = it }
    }

    /** Blocks: for a caller already on an I/O thread, such as the player's data source. */
    fun resolveNow(url: String): String {
        val link = MediaServer.parse(url) ?: return url
        val signedIn = login()?.takeIf { it.kind.isMediaServer } ?: throw IOException("Not signed in to this media server")
        return when (link.product) {
            MediaServer.Product.Plex -> plex(signedIn).streamFor(link.id)
            MediaServer.Product.Jellyfin, MediaServer.Product.Emby -> emby(signedIn).streamFor(link.id)
        }
    }

    suspend fun resolve(url: String): String = if (MediaServer.isLink(url)) withContext(Dispatchers.IO) { resolveNow(url) } else url
}
