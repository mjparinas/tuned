package com.tunedplayer.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.workDataOf
import com.tunedplayer.app.data.SyncProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Background refresh: the playlist and the guide each on their own period (Settings › Background refresh), only
 * with a network and not on low battery. 0 hours turns one off. "Refresh now" runs the same worker at once.
 */
class SyncScheduler(
    context: Context,
    /** A refresh was asked for: it shouldn't wait out the quiet start ([QuietStart.openNow]). */
    private val onAsked: () -> Unit = {},
) {
    // Looked up on first use, so WorkManager doesn't start before the first frame.
    private val work by lazy { WorkManager.getInstance(context) }

    fun schedule(
        playlistHours: Int,
        guideHours: Int,
        unmeteredOnly: Boolean,
    ) {
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
        plan(PLAYLIST, playlistHours, SyncWorker.MODE_ALL, constraints)
        plan(GUIDE, guideHours, SyncWorker.MODE_GUIDE, constraints)
    }

    private fun plan(
        name: String,
        hours: Int,
        mode: String,
        constraints: Constraints,
    ) {
        if (hours <= 0) {
            work.cancelUniqueWork(name)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(hours.toLong(), TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .setInputData(workDataOf(SyncWorker.KEY_MODE to mode))
                .build()
        work.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Refresh now (pull to refresh, Settings, and the first download after sign-in). Expedited, so it starts at once,
     * and the worker runs as a foreground service so a long download survives leaving the app. [first] replaces any
     * refresh already queued (a new login) and reports a failure at once instead of retrying later.
     */
    fun refreshNow(
        guideOnly: Boolean = false,
        first: Boolean = false,
    ) {
        onAsked()
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(
                    workDataOf(
                        SyncWorker.KEY_MODE to if (guideOnly) SyncWorker.MODE_GUIDE else SyncWorker.MODE_ALL,
                        SyncWorker.KEY_FIRST to first,
                    ),
                ).build()
        work.enqueueUniqueWork(NOW, if (first) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /**
     * True while a refresh the person started is queued or running. When it has ended and the cache is still empty with
     * no error saved, the first-run screen says so with Retry rather than waiting on a download that isn't coming.
     */
    val requestedPending: Flow<Boolean> by lazy {
        work.getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(NOW)).map { infos -> infos.any { !it.state.isFinished } }
    }

    /** Retry on the first-run screen: replaces a refresh that is stuck queued or running, which [refreshNow] would keep. */
    fun retryFirst() = refreshNow(first = true)

    /** True while any refresh runs, for the progress bar on the lists. */
    val refreshing: Flow<Boolean> by lazy {
        work.getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(NOW, PLAYLIST, GUIDE)).map { infos ->
            infos.any { it.state == WorkInfo.State.RUNNING }
        }
    }

    /** What the running refresh is doing, for the first download's screen; null when none runs or it hasn't reported yet. */
    val progress: Flow<SyncProgress?> by lazy { progressOf(NOW, PLAYLIST, GUIDE) }

    /**
     * The same, only for a refresh the person started (Refresh now, pull to refresh, a new playlist), for the bar
     * above the lists. Scheduled refreshes run unseen: the cache stays usable throughout, so there is nothing to wait
     * for (Melvin, 2026-11-18: "i see 'saving channels' a lot, why").
     */
    val requestedProgress: Flow<SyncProgress?> by lazy { progressOf(NOW) }

    /** True while a refresh the person started runs, for the pull-to-refresh spinner. */
    val requestedRefreshing: Flow<Boolean> by lazy {
        work.getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(NOW)).map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
    }

    private fun progressOf(vararg names: String): Flow<SyncProgress?> =
        work.getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(*names)).map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let { SyncWorker.progressOf(it.progress) }
        }

    fun cancelAll() {
        work.cancelUniqueWork(NOW)
        work.cancelUniqueWork(PLAYLIST)
        work.cancelUniqueWork(GUIDE)
    }

    private companion object {
        const val PLAYLIST = "refresh-playlist"
        const val GUIDE = "refresh-guide"
        const val NOW = "refresh-now"
    }
}
