package app.tuned.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient

/**
 * Where the player reads from. Streams go through OkHttp; saved films and episodes are `file://` URIs, which OkHttp
 * can't open, so [DefaultDataSource] reads those from the device and hands only network URIs to OkHttp. Without it a
 * downloaded episode failed to play (Melvin, 2026-11-17).
 */
@OptIn(UnstableApi::class)
object PlayerSources {
    fun factory(
        context: Context,
        http: OkHttpClient,
        userAgent: String,
        /** Wraps the network source, for the live rewind copy ([LiveTimeshift.factory]). */
        network: (DataSource.Factory) -> DataSource.Factory = { it },
    ): DataSource.Factory = DefaultDataSource.Factory(context, network(OkHttpDataSource.Factory(http).setUserAgent(userAgent)))
}
