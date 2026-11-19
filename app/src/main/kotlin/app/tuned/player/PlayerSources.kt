package app.tuned.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.tuned.core.Stalker
import okhttp3.OkHttpClient

/**
 * Where the player reads from. Streams go through OkHttp; saved films and episodes are `file://` URIs, which OkHttp
 * can't open, so [DefaultDataSource] reads those from the device and hands only network URIs to OkHttp. Without it a
 * downloaded episode failed to play (Melvin, 2026-11-17).
 */
@OptIn(UnstableApi::class)
object PlayerSources {
    /**
     * [headers] gives the headers a playlist asks for on a stream's host (a user agent or referrer, see
     * `core/StreamLink`), added to every request there, including an HLS stream's segments. They win over the app's
     * user agent, which is why it goes in as a default property rather than through `setUserAgent`, which would send a
     * second User-Agent header instead of replacing it.
     */
    fun factory(
        context: Context,
        http: OkHttpClient,
        userAgent: String,
        headers: (Uri) -> Map<String, String> = { emptyMap() },
        /** Wraps the network source, for the live rewind copy ([LiveTimeshift.factory]). */
        network: (DataSource.Factory) -> DataSource.Factory = { it },
        /**
         * A Stalker portal's channel turned into the stream to open (`data/StalkerLinks`), asked for on the loader thread
         * each time the channel opens, since the portal's links expire. The live rewind copy sees the stored link, which
         * stays the same across reconnects where the real one doesn't.
         */
        portal: (String) -> String = { it },
    ): DataSource.Factory {
        val okhttp = OkHttpDataSource.Factory(http).setDefaultRequestProperties(mapOf("User-Agent" to userAgent))
        val withHeaders =
            ResolvingDataSource.Factory(okhttp) { spec ->
                val extra = headers(spec.uri)
                if (extra.isEmpty()) spec else spec.withAdditionalHeaders(extra)
            }
        val fromPortal =
            ResolvingDataSource.Factory(withHeaders) { spec ->
                if (!Stalker.isLink(spec.uri.toString())) {
                    spec
                } else {
                    // Portals' stream servers often serve only what says it is a MAG box.
                    spec.withUri(portal(spec.uri.toString()).toUri()).withAdditionalHeaders(mapOf("User-Agent" to Stalker.USER_AGENT))
                }
            }
        return DefaultDataSource.Factory(context, network(fromPortal))
    }
}
