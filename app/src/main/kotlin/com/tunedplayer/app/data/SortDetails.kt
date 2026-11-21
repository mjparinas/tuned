package com.tunedplayer.app.data

import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The films' and shows' times added and ratings now, for a cache from before the sorts that no refresh has read since
 * (Melvin, 2026-11-20: "sorting on the movies page or the tv page doesn't do anything"). The update to 2.2.0 left every
 * title without a time or rating until the next scheduled refresh, up to a day away, so Recently added and Top rated
 * kept the playlist's order. These are lists through the Xtream API ([XtreamDetails]), the ones a refresh reads, not
 * streams. Nothing happens for other logins, or once any title has a time or a rating: refreshes keep them current.
 */
class SortDetails(
    private val db: TunedDatabase,
    private val http: OkHttpClient,
    /** The login in use ([CredentialStore.load]). */
    private val currentLogin: () -> Credentials?,
    private val settings: SettingsRepository,
) {
    private val lock = Mutex()

    suspend fun fillIfMissing() {
        val login = currentLogin()?.takeIf { it.kind == Credentials.Kind.Xtream } ?: return
        lock.withLock {
            if (!missing()) return
            AppLog.i(TAG) { "Reading times added and ratings for the Movies and Shows sorts" }
            val agent =
                settings.settings
                    .first()
                    .userAgent
                    .ifBlank { null }
            val details = XtreamDetails(db, XtreamImport(http, login, agent))
            attempt(TAG, "Film details failed") { details.films() }
            attempt(TAG, "Show details failed") { details.shows() }
        }
    }

    /** Films or episodes in the cache, none of them with a time added or a rating. */
    private suspend fun missing(): Boolean = withContext(Dispatchers.IO) {
        db.openHelper.readableDatabase
            .query(KNOWN)
            .use { c -> c.moveToFirst() && c.getLong(0) > 0 && c.getLong(1) == 0L }
    }

    private companion object {
        const val TAG = "SortDetails"
        const val KNOWN = "SELECT COUNT(*), COUNT(CASE WHEN added > 0 OR rating IS NOT NULL THEN 1 END) FROM items WHERE kind != 0"
    }
}
