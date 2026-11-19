package app.tuned.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.tuned.R
import app.tuned.container
import app.tuned.data.SyncProgress
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.logFailures
import app.tuned.ui.SyncText
import app.tuned.updates.UpdateNotice
import app.tuned.widget.Widgets
import java.io.IOException

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private var lastReport = 0L
    private var lastStep: SyncProgress.Step? = null
    private var foreground = false

    override suspend fun doWork(): Result = logFailures(TAG, failed = { Result.failure() }) { refresh() }

    private suspend fun refresh(): Result {
        val container = applicationContext.container
        if (container.credentials.load() == null) return Result.success()
        // A 400 MB playlist on a slow server can take many minutes. Running as a foreground service keeps the
        // download going when the screen turns off or another app comes forward (Android otherwise cuts the
        // connection: "Software caused connection abort"). Android 12+ refuses this when started from the background;
        // the work then runs as normal background work.
        foreground =
            try {
                setForeground(foregroundInfo(null))
                true
            } catch (_: IllegalStateException) {
                false
            }
        return try {
            if (inputData.getString(KEY_MODE) == MODE_GUIDE) {
                container.sync.refreshGuide(::report)
            } else {
                container.sync.refreshAll(::report)
            }
            // New guide data: alerts for followed teams' next games, and the widgets' "on now" lines move on too.
            attempt(TAG, "Couldn't set event alerts") { container.events.scheduleAlerts() }
            Widgets.refresh(applicationContext)
            // A copy left running for days still hears about a new release.
            UpdateNotice.checkInBackground(applicationContext)
            Result.success()
        } catch (e: IOException) {
            AppLog.w(TAG, "Refresh failed (attempt ${runAttemptCount + 1})", e)
            // The download already retried with backoff; the first sign-in reports at once instead of waiting for more.
            if (!inputData.getBoolean(KEY_FIRST, false) && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /** Hands progress to the screens (WorkInfo.progress) and the notification, a few times a second at most. */
    private fun report(progress: SyncProgress) {
        val now = SystemClock.elapsedRealtime()
        if (progress.step == lastStep && now - lastReport < REPORT_EVERY_MS) return
        lastStep = progress.step
        lastReport = now
        setProgressAsync(progress.toData())
        if (foreground) setForegroundAsync(foregroundInfo(progress))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(progress: SyncProgress?): ForegroundInfo {
        val notification = notification(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** A quiet notification with the same progress bar as the app. */
    private fun notification(progress: SyncProgress?): Notification {
        val context = applicationContext
        NotificationChannels.ensure(context)
        val fraction = progress?.fraction
        return NotificationCompat
            .Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tuned)
            .setContentTitle(context.getString(R.string.sync_running))
            .apply { progress?.let { setContentText(SyncText.describe(context, it)) } }
            .setProgress(PROGRESS_MAX, ((fraction ?: 0f) * PROGRESS_MAX).toInt(), fraction == null)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_FIRST = "first"
        const val MODE_ALL = "all"
        const val MODE_GUIDE = "guide"
        private const val TAG = "Sync"
        private const val KEY_STEP = "step"
        private const val KEY_DONE = "done"
        private const val KEY_TOTAL = "total"
        private const val CHANNEL = NotificationChannels.SYNC
        private const val NOTIFICATION_ID = 7
        private const val MAX_ATTEMPTS = 3
        private const val REPORT_EVERY_MS = 500L
        private const val PROGRESS_MAX = 1_000

        private fun SyncProgress.toData(): Data = workDataOf(KEY_STEP to step.name, KEY_DONE to done, KEY_TOTAL to total)

        /** The progress a running refresh last reported, or null before its first report. */
        fun progressOf(data: Data): SyncProgress? {
            val step = data.getString(KEY_STEP)?.let { name -> SyncProgress.Step.entries.firstOrNull { it.name == name } } ?: return null
            return SyncProgress(step, data.getLong(KEY_DONE, 0), data.getLong(KEY_TOTAL, -1))
        }
    }
}
