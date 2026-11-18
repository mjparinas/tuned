package app.tuned.data

import app.tuned.BuildConfig
import app.tuned.core.XtreamAccount
import app.tuned.core.XtreamUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * The account's status (Melvin, 2026-11-17, from the app audit): expiry date and how many connections are in use, from
 * Xtream's `player_api.php`. It is a small answer, not a stream, so asking never uses one of the account's connections.
 * The answer is kept in memory and asked again only when older than [FRESH_MS], so nothing waits on it.
 */
class AccountRepository(
    private val http: OkHttpClient,
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
    /** True while Tuned itself holds a stream: the player, or a download (which counts as a connection too). */
    private val ownStream: StateFlow<Boolean>,
) {
    /** The last answer and when it came, taken while Tuned had no stream open (see [noStreamSince]). */
    data class Snapshot(
        val info: XtreamAccount.Info,
        val at: Long,
        /** Tuned's own player was closed for at least [OWN_STREAM_GONE_MS] when it was taken. */
        val withoutOwnStream: Boolean,
    )

    private val state = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = state

    /** When Tuned last closed its own stream (or 0 if it hasn't opened one); set by the player and downloads. */
    @Volatile
    var noStreamSince: Long = 0

    /** Asks the server again if the last answer is older than [FRESH_MS]. Plain M3U logins have no account call. */
    suspend fun refreshIfOld() {
        val last = state.value
        if (last != null && System.currentTimeMillis() - last.at < FRESH_MS) return
        refresh()
    }

    suspend fun refresh() {
        val login = credentials.load() ?: return
        if (login.playlistUrl.isNotBlank()) return
        val agent =
            settings.settings
                .first()
                .userAgent
                .ifBlank { "Tuned/${BuildConfig.VERSION_NAME}" }
        val request =
            Request
                .Builder()
                .url(XtreamUrls.account(login.server, login.username, login.password))
                .header("User-Agent", agent)
                .build()
        val info =
            withContext(Dispatchers.IO) {
                try {
                    http.newCall(request).execute().use { r ->
                        if (r.isSuccessful) XtreamAccount.info(r.body.string().take(MAX_ANSWER)) else null
                    }
                } catch (_: IOException) {
                    null
                }
            } ?: return
        val now = System.currentTimeMillis()
        state.value = Snapshot(info, now, withoutOwnStream = withoutOwnStream(ownStream.value, noStreamSince, now))
    }

    /**
     * True when a recent answer, taken while Tuned had no stream of its own open, says every connection is in use
     * (someone watching on another device). Opening another stream then could go over the limit.
     */
    fun fullElsewhere(now: Long = System.currentTimeMillis()): XtreamAccount.Info? {
        val s = state.value ?: return null
        return s.info.takeIf { s.withoutOwnStream && it.full && now - s.at < FRESH_MS }
    }

    fun clear() {
        state.value = null
    }

    companion object {
        const val FRESH_MS = 3 * 60_000L

        /**
         * Whether an answer taken [now] counts only other devices: nothing of Tuned's is open, and its last stream
         * closed long enough ago for the server to stop counting it. A download running while Home asked used to
         * read as "every connection is in use elsewhere", so the player waited for a press of play.
         */
        fun withoutOwnStream(
            ownStreamOpen: Boolean,
            noStreamSince: Long,
            now: Long,
        ): Boolean = !ownStreamOpen && now - noStreamSince >= OWN_STREAM_GONE_MS

        /** Servers take a little while to stop counting a closed stream. */
        const val OWN_STREAM_GONE_MS = 60_000L
        private const val MAX_ANSWER = 64 * 1024
    }
}
