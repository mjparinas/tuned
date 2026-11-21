package com.tunedplayer.app.work

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tunedplayer.app.container
import com.tunedplayer.app.data.ServerFlags
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.logFailures
import java.util.concurrent.TimeUnit

/**
 * Runs the backup in the Tuned account ([com.tunedplayer.app.data.CloudSyncRepository]) in the background, once a network is there: when
 * the app opens (to take what another device changed) and when it leaves the screen (to send what changed here).
 */
class CloudSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = logFailures(TAG, failed = { Result.failure() }) {
        // After the first screen is up: a cold start spends the device on what the person is waiting for.
        applicationContext.container.quietStart.await()
        val sync = applicationContext.container.cloudSync
        // The admin's `account_backup` switch pauses the hourly run too.
        if (!ServerFlags.now.value.accountBackup) return@logFailures Result.success()
        val saved = sync.run()
        // Every signed-in app keeps its channel list in the account (Melvin, 2026-11-20), for the website's Channels and guide
        // page and for a new or reset device whose provider download fails. It doesn't wait on the backup: a backup that
        // kept failing once held the list back too, and the website showed no channels (2026-11-21).
        attempt(TAG, "Couldn't save the channel list to the account") { applicationContext.container.sharedChannels.run() }
        if (saved) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "CloudSync"
        private const val NAME = "cloud-sync"
        private const val HOURLY = "cloud-sync-hourly"
        private const val BACKOFF_MIN = 5L

        fun schedule(context: Context) {
            val sync = context.container.cloudSync
            // The admin's `account_backup` switch pauses backups for everyone; they carry on when it's back on.
            if (!sync.signedIn.value || !ServerFlags.now.value.accountBackup) return
            val request =
                OneTimeWorkRequestBuilder<CloudSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MIN, TimeUnit.MINUTES)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * Syncs each time the app opens and each time it leaves the screen, and every hour in between, so a playlist
         * added on the website reaches a TV left on all evening. Call once, from the main thread.
         */
        fun watch(context: Context) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) schedule(context)
                    if (event == Lifecycle.Event.ON_START) scheduleHourly(context)
                },
            )
        }

        /** The hourly run; it does nothing while signed out, and the next sign-in keeps it going. */
        private fun scheduleHourly(context: Context) {
            if (!context.container.cloudSync.signedIn.value) return
            val request =
                PeriodicWorkRequestBuilder<CloudSyncWorker>(1, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MIN, TimeUnit.MINUTES)
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(HOURLY, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
