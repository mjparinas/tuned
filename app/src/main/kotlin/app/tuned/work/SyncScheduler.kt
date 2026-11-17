package app.tuned.work

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Background refresh: the playlist and the guide each on their own period (Settings › Background refresh), only
 * with a network and not on low battery. 0 hours turns one off. "Refresh now" runs the same worker at once.
 */
class SyncScheduler(
    context: Context,
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

    /** Refresh now (first sign-in, pull to refresh, Settings). Expedited, so it starts at once. */
    fun refreshNow(guideOnly: Boolean = false) {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(SyncWorker.KEY_MODE to if (guideOnly) SyncWorker.MODE_GUIDE else SyncWorker.MODE_ALL))
                .build()
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** True while any refresh runs, for the progress bar on the lists. */
    val refreshing: Flow<Boolean> by lazy {
        work.getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(NOW, PLAYLIST, GUIDE)).map { infos ->
            infos.any { it.state == WorkInfo.State.RUNNING }
        }
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
