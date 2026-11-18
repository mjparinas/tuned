package app.tuned.work

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tuned.container
import app.tuned.diagnostics.logFailures
import java.util.concurrent.TimeUnit

/**
 * Runs sync between devices ([app.tuned.data.CloudSyncRepository]) in the background, once a network is there: when
 * the app opens (to take what another device changed) and when it leaves the screen (to send what changed here).
 */
class CloudSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = logFailures(TAG, failed = { Result.failure() }) {
        if (applicationContext.container.cloudSync.run()) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "CloudSync"
        private const val NAME = "cloud-sync"
        private const val BACKOFF_MIN = 5L

        fun schedule(context: Context) {
            val sync = context.container.cloudSync
            if (!sync.available || !sync.signedIn.value) return
            val request =
                OneTimeWorkRequestBuilder<CloudSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MIN, TimeUnit.MINUTES)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Syncs each time the app opens and each time it leaves the screen. Call once, from the main thread. */
        fun watch(context: Context) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) schedule(context)
                },
            )
        }
    }
}
