package com.tunedplayer.app.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.data.ServerFlags
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.logFailures
import com.tunedplayer.app.ui.ProgressEta
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
    private var startedAt = 0L

    override suspend fun doWork(): Result = logFailures("Scan", failed = { Result.failure() }) {
        val container = applicationContext.container
        container.quietStart.await()
        if (container.credentials.load() == null) return@logFailures Result.success()
        // Android 12+ may refuse from the background; it runs anyway.
        attempt("Scan", "Couldn't run as a foreground service") { setForeground(foregroundInfo(Progress(0, 0, 0))) }
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
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        val progress = Progress(done, total, startedAt)
        setProgressAsync(progress.toData())
        setForegroundAsync(foregroundInfo(progress))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(Progress(0, 0, 0))

    private fun foregroundInfo(progress: Progress): ForegroundInfo {
        val notification = notification(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(progress: Progress): Notification {
        val context = applicationContext
        NotificationChannels.ensure(context)
        val text =
            if (progress.total > 0) {
                ProgressEta.withEta(
                    context,
                    context.resources.getQuantityString(R.plurals.scan_progress, progress.total, progress.done, progress.total),
                    progress.done.toLong(),
                    progress.total.toLong(),
                    progress.startedAt,
                )
            } else {
                null
            }
        return NotificationCompat
            .Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tuned)
            .setContentTitle(context.getString(R.string.scan_running))
            .apply { text?.let(::setContentText) }
            .setProgress(progress.total, progress.done, progress.total == 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Starting, stopping and following the scan. */
    class Control(
        context: Context,
        /** A scan was asked for: it shouldn't wait out the quiet start ([QuietStart.openNow]). */
        private val onAsked: () -> Unit = {},
    ) {
        private val work = WorkManager.getInstance(context)

        fun start() {
            // The admin's `channel_scan` switch (docs/decisions/admin.md).
            if (!ServerFlags.now.value.channelScan) return
            onAsked()
            val request =
                OneTimeWorkRequestBuilder<ScanWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            work.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun stop() {
            work.cancelUniqueWork(NAME)
        }

        /** How far a running scan has got, or null when none runs. */
        val progress: Flow<Progress?> =
            work.getWorkInfosForUniqueWorkFlow(NAME).map { infos ->
                val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                running?.let { Progress.of(it.progress) }
            }
    }

    /** How far a scan has got: [done] of [total], with [startedAt] for the time left. */
    data class Progress(
        val done: Int,
        val total: Int,
        val startedAt: Long = 0,
    ) {
        fun toData() = workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_STARTED to startedAt)

        companion object {
            fun of(data: Data) = Progress(data.getInt(KEY_DONE, 0), data.getInt(KEY_TOTAL, 0), data.getLong(KEY_STARTED, 0))
        }
    }

    private companion object {
        const val NAME = "channel-scan"
        const val CHANNEL = NotificationChannels.SCAN
        const val NOTIFICATION_ID = 11
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_STARTED = "startedAt"
        const val REPORT_EVERY_MS = 1_000L
    }
}
