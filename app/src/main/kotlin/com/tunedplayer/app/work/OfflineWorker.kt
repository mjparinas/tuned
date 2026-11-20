package com.tunedplayer.app.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.Stalker
import com.tunedplayer.app.core.StreamLink
import com.tunedplayer.app.data.Downloader
import com.tunedplayer.app.data.db.DownloadEntity
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.logFailures
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

    override suspend fun doWork(): Result {
        // Before the connection opens: a download resumed at start waits for the first screen.
        applicationContext.container.quietStart.await()
        return downloadOnce()
    }

    private suspend fun downloadOnce(): Result = try {
        applicationContext.container.downloadOpen.value = true
        logFailures(TAG, failed = { Result.failure() }) { download() }
    } finally {
        // Reached only once the connection is closed (Downloader hangs it up when stopped); the player waits for it.
        applicationContext.container.downloadOpen.value = false
        // The server takes a while to stop counting the download's connection; see [AccountRepository.noStreamSince].
        applicationContext.container.account.noStreamSince = System.currentTimeMillis()
    }

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
            // Something started playing (the player, or a TV) between being queued and now: stop; the queue starts
            // again when it ends.
            if (container.watching() || container.recordingOpen.value) return Result.success()
            val row = dao.next() ?: return Result.success()
            // Its page first (details, poster, cast photos), so it looks the same offline; a few small requests.
            attempt(TAG, "Couldn't save the artwork for ${row.name}") { container.savedArtwork.keep(row.itemId) }
            try {
                // A user agent the playlist asks for wins over the app's.
                val link = StreamLink.split(row.url)
                // A portal's film or episode is asked for its stream first, as a box would, with the box's user agent.
                val portal = Stalker.isLink(link.url)
                val url = if (portal) container.stalker.resolve(link.url) else link.url
                val agent = if (portal) Stalker.USER_AGENT else link.headers[StreamLink.USER_AGENT] ?: ua
                downloader.download(url, File(row.path), agent, keepPart = true) { bytes, total -> report(row, bytes, total) }
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
        NotificationChannels.ensure(context)
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
        private const val CHANNEL = NotificationChannels.DOWNLOADS
        private const val NOTIFICATION_ID = 8
        private const val REPORT_EVERY_MS = 500L
        private const val PROGRESS_MAX = 1_000
    }
}
