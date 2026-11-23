package com.tunedplayer.app.data

import android.database.Cursor
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.Trakt
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.data.db.offMain
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Imports Trakt's watched history into Tuned's own for the profile in use (docs/decisions/trakt.md): films and episodes
 * seen elsewhere are marked watched here when the playlist has a matching title (and year when both sides have one).
 * Settings › Playlists and account › Import watched history starts it; [progress] drives the row's spinner and summary.
 */
class TraktHistoryImport(
    private val account: TraktAccount,
    private val db: TunedDatabase,
) {
    /** Where the import is, for the Settings row. */
    sealed interface Progress {
        data object Starting : Progress

        data object ReadingPlaylist : Progress

        data object FetchingFilms : Progress

        data object FetchingShows : Progress

        data class Marking(
            val done: Int,
            val total: Int,
            val startedAt: Long,
        ) : Progress

        data class Done(
            val films: Int,
            val episodes: Int,
        ) : Progress

        data object Failed : Progress
    }

    private val progressFlow = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = progressFlow.asStateFlow()

    private val lock = Mutex()

    /** Runs an import, or does nothing when one is already under way. Cancelling the caller stops it. */
    suspend fun run() = lock.withLock {
        if (account.session.value == null) {
            progressFlow.value = Progress.Failed
            return@withLock
        }
        progressFlow.value = Progress.Starting
        try {
            val ended =
                attempt(TAG, "Trakt history import failed") {
                    progressFlow.value = Progress.ReadingPlaylist
                    val filmsLocal = playlistFilms()
                    val episodesLocal = playlistEpisodes()
                    progressFlow.value = Progress.FetchingFilms
                    val watchedFilms = account.watchedFilms() ?: return@attempt Progress.Failed
                    progressFlow.value = Progress.FetchingShows
                    val watchedEpisodes = account.watchedEpisodes() ?: return@attempt Progress.Failed
                    val filmMatches = Trakt.matchFilms(filmsLocal, watchedFilms)
                    val episodeMatches = Trakt.matchEpisodes(episodesLocal, watchedEpisodes)
                    val all = filmMatches + episodeMatches
                    if (all.isEmpty()) {
                        AppLog.i(TAG) {
                            "Trakt import matched nothing (${watchedFilms.size} films, ${watchedEpisodes.size} episodes on Trakt)"
                        }
                        return@attempt Progress.Done(0, 0)
                    }
                    val startedAt = System.currentTimeMillis()
                    progressFlow.value = Progress.Marking(0, all.size, startedAt)
                    // Chunk so the row can show how far marking has got on a large import.
                    var done = 0
                    for (chunk in all.chunked(MARK_CHUNK)) {
                        offMain { db.library().markWatchedFromTrakt(chunk) }
                        done += chunk.size
                        progressFlow.value = Progress.Marking(done, all.size, startedAt)
                    }
                    val filmMarked = filmMatches.map { it.itemId }.toHashSet().size
                    val episodeMarked = episodeMatches.map { it.itemId }.toHashSet().size
                    AppLog.i(TAG) { "Trakt import marked $filmMarked films and $episodeMarked episodes watched" }
                    Progress.Done(filmMarked, episodeMarked)
                }.getOrElse { Progress.Failed }
            progressFlow.value = ended
        } catch (e: CancellationException) {
            progressFlow.value = null
            throw e
        }
    }

    /** Clears a finished or failed result so the row goes back to its idle summary. */
    fun clearResult() {
        when (progressFlow.value) {
            is Progress.Done, Progress.Failed -> progressFlow.value = null
            else -> Unit
        }
    }

    /** Films in the playlist as Trakt matching rows (openHelper so ItemDao stays under detekt's function limit). */
    private suspend fun playlistFilms(): List<Trakt.LocalFilm> = offMain {
        db.openHelper.readableDatabase.query(FILMS_SQL).use { c ->
            buildList {
                while (c.moveToNext()) {
                    attempt(TAG, "Skipping a film row for Trakt import") {
                        add(Trakt.localFilm(c.getLong(0), c.getString(1), c.intOrNull(2)))
                    }
                }
            }
        }
    }

    /** Episodes with a show name, season and number, as Trakt matching rows. */
    private suspend fun playlistEpisodes(): List<Trakt.LocalEpisode> = offMain {
        db.openHelper.readableDatabase.query(EPISODES_SQL).use { c ->
            buildList {
                while (c.moveToNext()) {
                    attempt(TAG, "Skipping an episode row for Trakt import") {
                        add(Trakt.localEpisode(c.getLong(0), c.getString(1), c.intOrNull(2), c.getInt(3), c.getInt(4)))
                    }
                }
            }
        }
    }

    private fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

    private companion object {
        const val TAG = "TraktImport"
        const val MARK_CHUNK = 100
        val FILMS_SQL = "SELECT id, name, year FROM items WHERE kind = ${ContentKind.MOVIE.code}"
        val EPISODES_SQL =
            "SELECT id, showName, year, season, episode FROM items WHERE kind = ${ContentKind.SERIES.code} " +
                "AND showName IS NOT NULL AND season IS NOT NULL AND episode IS NOT NULL"
    }
}
