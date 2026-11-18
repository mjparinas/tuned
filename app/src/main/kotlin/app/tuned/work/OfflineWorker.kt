package app.tuned.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.tuned.R
import app.tuned.container
import app.tuned.core.StreamLink
import app.tuned.data.Downloader
import app.tuned.data.db.DownloadEntity
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.logFailures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** Downloads the queued films and episodes, oldest first, one at a time. */
class OfflineWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private var lastReport = 0L

    override suspend fun doWork(): Result = logFailures(TAG, failed = { Result.failure() }) { download() }

    private suspend fun download(): Result {
        val container = applicationContext.container
        val dao = container.db.downloads()
        val downloader = Downloader(container.http)
        val ua =
            container.settings.settings
                .first()
                .userAgent
        // A film is large; as a foreground service the download carries on with the screen off.
        attempt(TAG, "Couldn't run as a foreground service") { setForeground(foregroundInfo(null)) }
        while (true) {
            // The player opened between being queued and now: stop, and the player starts the queue again on closing.
            if (container.playerOpen.value) return Result.success()
            val row = dao.next() ?: return Result.success()
            // Its page first (details, poster, cast photos), so it looks the same offline; a few small requests.
            attempt(TAG, "Couldn't save the artwork for ${row.name}") { container.savedArtwork.keep(row.itemId) }
            try {
                // A user agent the playlist asks for wins over the app's.
                val link = StreamLink.split(row.url)
                val agent = link.headers[StreamLink.USER_AGENT] ?: ua
                downloader.download(link.url, File(row.path), agent, { bytes, total -> report(row, bytes, total) }, keepPart = true)
                val size = File(row.path).length()
                dao.progress(row.itemId, size, size)
                dao.state(row.itemId, DownloadEntity.DONE)
            } catch (e: IOException) {
                AppLog.w(TAG, "Download failed: ${row.name}", e)
                dao.state(row.itemId, DownloadEntity.FAILED)
            } catch (e: IllegalArgumentException) {
                // Not a usable address: marked failed so the queue moves on instead of trying it forever.
                AppLog.w(TAG, "Can't download ${row.name}", e)
                dao.state(row.itemId, DownloadEntity.FAILED)
            }
        }
    }

    /** Saves progress for the Downloads screen and the notification, twice a second at most. */
    private fun report(
        row: DownloadEntity,
        bytes: Long,
        total: Long,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReport < REPORT_EVERY_MS) return
        lastReport = now
        val dao = applicationContext.container.db.downloads()
        applicationContext.container.appScope.launch { dao.progress(row.itemId, bytes, total) }
        setForegroundAsync(foregroundInfo(row.name to (if (total > 0) (bytes * PROGRESS_MAX / total).toInt() else -1)))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(progress: Pair<String, Int>?): ForegroundInfo {
        val notification = notification(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(progress: Pair<String, Int>?): Notification {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW),
        )
        val percent = progress?.second ?: -1
        return NotificationCompat
            .Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tuned)
            .setContentTitle(context.getString(R.string.downloads_running))
            .apply { progress?.let { setContentText(it.first) } }
            .setProgress(PROGRESS_MAX, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val NAME = "offline"
        private const val TAG = "Downloads"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 8
        private const val REPORT_EVERY_MS = 500L
        private const val PROGRESS_MAX = 1_000
    }
}
