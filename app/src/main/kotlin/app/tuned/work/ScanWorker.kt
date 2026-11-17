package app.tuned.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.tuned.R
import app.tuned.container
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.logFailures
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Runs a channel scan (Settings › Channel health) as a foreground job, so it keeps going with the screen off; a scan of
 * a few thousand channels takes a while at one channel at a time. Only started by hand.
 */
class ScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private var lastReport = 0L

    override suspend fun doWork(): Result = logFailures("Scan", failed = { Result.failure() }) {
        val container = applicationContext.container
        if (container.credentials.load() == null) return@logFailures Result.success()
        // Android 12+ may refuse from the background; it runs anyway.
        attempt("Scan", "Couldn't run as a foreground service") { setForeground(foregroundInfo(0, 0)) }
        container.channelScan.scan { done, total -> report(done, total) }
        Result.success()
    }

    private fun report(
        done: Int,
        total: Int,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (done < total && now - lastReport < REPORT_EVERY_MS) return
        lastReport = now
        setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
        setForegroundAsync(foregroundInfo(done, total))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(
        done: Int,
        total: Int,
    ): ForegroundInfo {
        val notification = notification(done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(
        done: Int,
        total: Int,
    ): Notification {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.scan_channel), NotificationManager.IMPORTANCE_MIN),
        )
        return NotificationCompat
            .Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tuned)
            .setContentTitle(context.getString(R.string.scan_running))
            .apply { if (total > 0) setContentText(context.resources.getQuantityString(R.plurals.scan_progress, total, done, total)) }
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Starting, stopping and following the scan. */
    class Control(
        context: Context,
    ) {
        private val work = WorkManager.getInstance(context)

        fun start() {
            val request =
                OneTimeWorkRequestBuilder<ScanWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            work.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun stop() {
            work.cancelUniqueWork(NAME)
        }

        /** How far a running scan has got (done to total), or null when none runs. */
        val progress: Flow<Pair<Int, Int>?> =
            work.getWorkInfosForUniqueWorkFlow(NAME).map { infos ->
                val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                running?.let { it.progress.getInt(KEY_DONE, 0) to it.progress.getInt(KEY_TOTAL, 0) }
            }
    }

    private companion object {
        const val NAME = "channel-scan"
        const val CHANNEL = "scan"
        const val NOTIFICATION_ID = 11
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val REPORT_EVERY_MS = 1_000L
    }
}
