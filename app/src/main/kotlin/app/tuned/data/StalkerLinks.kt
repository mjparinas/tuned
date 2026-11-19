package app.tuned.data

import app.tuned.core.Stalker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * The signed-in portal, kept for the session so its token is reused, and the step that turns a stored `stalker://`
 * channel into the stream to open (`core/Stalker`). Every other link is handed back as it is, so callers can run
 * every stream through [resolve] without asking what kind of playlist is in use.
 */
class StalkerLinks(
    private val http: OkHttpClient,
    private val login: () -> Credentials?,
) {
    @Volatile private var current: StalkerPortal? = null

    /** The portal for [credentials], the same one while the same portal and MAC are signed in. */
    fun portal(credentials: Credentials): StalkerPortal {
        current?.takeIf { it.portal == credentials.server && it.mac == credentials.username }?.let { return it }
        return StalkerPortal(http, credentials.server, credentials.username).also { current = it }
    }

    /** Blocks: for a caller already on an I/O thread, such as the player's data source. */
    fun resolveNow(url: String): String {
        val link = Stalker.parse(url) ?: return url
        val signedIn = login()?.takeIf { it.kind == Credentials.Kind.Stalker } ?: throw IOException("Not signed in to this portal")
        return portal(signedIn).streamFor(link)
    }

    suspend fun resolve(url: String): String = if (Stalker.isLink(url)) withContext(Dispatchers.IO) { resolveNow(url) } else url
}
