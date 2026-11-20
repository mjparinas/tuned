package com.tunedplayer.app.data

import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.core.XtreamAccount
import com.tunedplayer.app.core.XtreamUrls
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The account's status (Melvin, 2026-11-17, from the app audit): expiry date and how many connections are in use, from
 * Xtream's `player_api.php`. It is a small answer, not a stream, so asking never uses one of the account's connections.
 * The answer is kept in memory and asked again only when older than [FRESH_MS], so nothing waits on it.
 */
class AccountRepository(
    private val http: OkHttpClient,
    /** The login in use ([CredentialStore.load]). */
    private val login: () -> Credentials?,
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

    /**
     * Never throws: Settings and Home ask this on their own, often while the playlist is still downloading, so a server
     * address OkHttp can't parse, a header it refuses or an answer it can't read is logged and the last answer kept,
     * rather than ending the app (a TV opening Settings during the first load crashed on it, 2026-11-20).
     */
    suspend fun refresh() {
        val login = login() ?: return
        if (login.playlistUrl.isNotBlank()) return
        val agent =
            settings.settings
                .first()
                .userAgent
                .ifBlank { "Tuned/${BuildConfig.VERSION_NAME}" }
        val info =
            withContext(Dispatchers.IO) {
                attempt(TAG, "Couldn't ask the server for the account's status") {
                    val request =
                        Request
                            .Builder()
                            .url(XtreamUrls.account(login.server, login.username, login.password))
                            .header("User-Agent", agent)
                            .build()
                    http.newCall(request).execute().use { r ->
                        // Only the first [MAX_ANSWER] bytes are read, so a server sending something huge can't run a
                        // TV that is busy saving the playlist out of memory.
                        if (r.isSuccessful) XtreamAccount.info(r.peekBody(MAX_ANSWER.toLong()).string()) else null
                    }
                }.getOrNull()
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
        private const val TAG = "Account"
        private const val MAX_ANSWER = 64 * 1024
    }
}
