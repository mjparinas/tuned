package com.tunedplayer.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.tunedplayer.app.R
import com.tunedplayer.app.data.WatchedHistoryImport
import com.tunedplayer.app.ui.ProgressEta

/** The lines that name the service, so Trakt's and Simkl's rows share everything else. */
data class HistoryImportTexts(
    @param:StringRes val films: Int,
    @param:StringRes val shows: Int,
    @param:StringRes val none: Int,
    @param:StringRes val failed: Int,
)

val TRAKT_IMPORT_TEXTS =
    HistoryImportTexts(R.string.trakt_import_films, R.string.trakt_import_shows, R.string.trakt_import_none, R.string.trakt_import_failed)

val SIMKL_IMPORT_TEXTS =
    HistoryImportTexts(R.string.simkl_import_films, R.string.simkl_import_shows, R.string.simkl_import_none, R.string.simkl_import_failed)

/**
 * Settings › Import watched history (docs/decisions/trakt.md, docs/decisions/simkl.md): a spinner and the step it is on
 * from the tap, a count and ETA while marking, then how many were marked, that nothing matched, or Try again.
 */
@Composable
fun HistoryImportRow(
    progress: WatchedHistoryImport.Progress?,
    texts: HistoryImportTexts,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val busy =
        progress != null &&
            progress !is WatchedHistoryImport.Progress.Done &&
            progress !is WatchedHistoryImport.Progress.Failed
    val summary =
        when (progress) {
            null -> {
                stringResource(R.string.trakt_import_summary)
            }

            WatchedHistoryImport.Progress.Starting, WatchedHistoryImport.Progress.ReadingPlaylist -> {
                stringResource(R.string.trakt_import_reading)
            }

            WatchedHistoryImport.Progress.FetchingFilms -> {
                stringResource(texts.films)
            }

            WatchedHistoryImport.Progress.FetchingShows -> {
                stringResource(texts.shows)
            }

            is WatchedHistoryImport.Progress.Marking -> {
                ProgressEta.withEta(
                    context,
                    stringResource(R.string.trakt_import_marking, progress.done, progress.total),
                    progress.done.toLong(),
                    progress.total.toLong(),
                    progress.startedAt,
                )
            }

            is WatchedHistoryImport.Progress.Done -> {
                if (progress.films == 0 && progress.episodes == 0) {
                    stringResource(texts.none)
                } else {
                    stringResource(
                        R.string.trakt_import_done,
                        pluralStringResource(R.plurals.trakt_import_films_count, progress.films, progress.films),
                        pluralStringResource(R.plurals.trakt_import_episodes_count, progress.episodes, progress.episodes),
                    )
                }
            }

            WatchedHistoryImport.Progress.Failed -> {
                stringResource(texts.failed)
            }
        }
    SettingRow(
        stringResource(R.string.trakt_import),
        summary,
        onClick = if (busy) null else onImport,
        trailing = if (busy) ({ RowSpinner() }) else null,
    )
}
