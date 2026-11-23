package com.tunedplayer.app.data

import com.tunedplayer.app.core.Trakt
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.data.db.offMain
import com.tunedplayer.app.diagnostics.AppLog
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
            progressFlow.value = Progress.ReadingPlaylist
            val filmsLocal =
                offMain {
                    db.items().traktFilmRows().map { Trakt.localFilm(it.id, it.name, it.year) }
                }
            val episodesLocal =
                offMain {
                    db.items().traktEpisodeRows().mapNotNull { row ->
                        val show = row.showName ?: return@mapNotNull null
                        val season = row.season ?: return@mapNotNull null
                        val number = row.episode ?: return@mapNotNull null
                        Trakt.localEpisode(row.id, show, row.year, season, number)
                    }
                }
            progressFlow.value = Progress.FetchingFilms
            val watchedFilms =
                account.watchedFilms() ?: run {
                    progressFlow.value = Progress.Failed
                    return@withLock
                }
            progressFlow.value = Progress.FetchingShows
            val watchedEpisodes =
                account.watchedEpisodes() ?: run {
                    progressFlow.value = Progress.Failed
                    return@withLock
                }
            val filmMatches = Trakt.matchFilms(filmsLocal, watchedFilms)
            val episodeMatches = Trakt.matchEpisodes(episodesLocal, watchedEpisodes)
            val all = filmMatches + episodeMatches
            if (all.isEmpty()) {
                AppLog.i(TAG) { "Trakt import matched nothing (${watchedFilms.size} films, ${watchedEpisodes.size} episodes on Trakt)" }
                progressFlow.value = Progress.Done(0, 0)
                return@withLock
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
            progressFlow.value = Progress.Done(filmMarked, episodeMarked)
        } catch (e: CancellationException) {
            progressFlow.value = null
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Trakt history import failed", e)
            progressFlow.value = Progress.Failed
        }
    }

    /** Clears a finished or failed result so the row goes back to its idle summary. */
    fun clearResult() {
        when (progressFlow.value) {
            is Progress.Done, Progress.Failed -> progressFlow.value = null
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "TraktImport"
        const val MARK_CHUNK = 100
    }
}
