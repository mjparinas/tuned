package com.tunedplayer.app.player

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.tunedplayer.app.AppContainer
import com.tunedplayer.app.data.db.HistoryEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where each film and episode was left, saved every few seconds, and live channels for the Recent filter. */
internal class PlaybackProgress(
    private val app: AppContainer,
) {
    suspend fun resumePosition(id: Long): Long {
        val h = app.db.library().progress(id) ?: return C.TIME_UNSET
        val nearlyDone = h.durationMs > 0 && h.positionMs > h.durationMs * 0.95
        return if (h.positionMs > 30_000 && !nearlyDone) h.positionMs else C.TIME_UNSET
    }

    /** Live channels go into history too (no position), for the Recent filter on Live TV. */
    fun rememberChannel(id: Long) {
        app.appScope.launch {
            app.db.library().saveProgress(HistoryEntity(id, 0, 0, System.currentTimeMillis()))
        }
    }

    suspend fun saveProgressEvery(exo: ExoPlayer) {
        while (true) {
            delay(10_000)
            saveProgress(exo)
        }
    }

    suspend fun saveProgress(exo: ExoPlayer) {
        val id =
            exo.currentMediaItem
                ?.mediaId
                ?.toLongOrNull()
                ?.takeIf { it != CatchUpLinks.ID } ?: return
        val duration = exo.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        app.db.library().saveProgress(HistoryEntity(id, exo.currentPosition, duration, System.currentTimeMillis()))
    }
}
