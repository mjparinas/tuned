package app.tuned.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.tuned.R
import app.tuned.container
import java.io.IOException

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.container
        if (container.credentials.load() == null) return Result.success()
        return try {
            if (inputData.getString(KEY_MODE) == MODE_GUIDE) container.sync.refreshGuide() else container.sync.refreshAll()
            Result.success()
        } catch (_: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    /** Expedited work on Android 11 and older runs as a foreground service, which needs a (quiet) notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = applicationContext.getString(R.string.sync_channel)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_MIN))
        }
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_tuned)
                .setContentTitle(applicationContext.getString(R.string.sync_running))
                .setOngoing(true)
                .setSilent(true)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_ALL = "all"
        const val MODE_GUIDE = "guide"
        private const val CHANNEL = "sync"
        private const val NOTIFICATION_ID = 7
        private const val MAX_ATTEMPTS = 3
    }
}
