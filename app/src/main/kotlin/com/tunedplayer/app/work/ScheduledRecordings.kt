package com.tunedplayer.app.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.RecordingWindow
import com.tunedplayer.app.core.Stalker
import com.tunedplayer.app.core.StreamLink
import com.tunedplayer.app.data.LiveCapture
import com.tunedplayer.app.data.Recordings
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.logFailures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Recording a programme from the guide while nothing plays (Melvin, 2026-11-20: "do 4, 6, 7, 8"; docs/decisions/player.md).
 * Each one is a WorkManager job, like a reminder ([Reminders]), so the schedule survives restarts with no table of its
 * own: the slot it holds on the connection is stored in its tags ([RecordingWindow.tag]), so a second recording at the
 * same time is refused. Switching playlists and signing out cancel them, since the channels belong to the playlist.
 */
class ScheduledRecordings(
    private val context: Context,
) {
    private val work by lazy { WorkManager.getInstance(context) }

    /** Every recording waiting or running. */
    val scheduled: Flow<List<RecordingWindow.Slot>> by lazy {
        work.getWorkInfosByTagFlow(TAG).map { infos -> infos.filter { !it.state.isFinished }.mapNotNull(::slot) }
    }

    /** Sets a recording; returns the one already set for then instead, when they would overlap (nothing is set). */
    suspend fun set(
        channelId: Long,
        title: String,
        start: Long,
        stop: Long,
        paddingMinutes: Int,
    ): RecordingWindow.Slot? {
        val key = key(channelId, start)
        val slot = RecordingWindow.slot(key, start, stop, paddingMinutes)
        RecordingWindow.clash(scheduled.first(), slot)?.let { return it }
        val request =
            OneTimeWorkRequestBuilder<RecordWorker>()
                .setInitialDelay((slot.from - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .addTag(RecordingWindow.tag(slot))
                .setInputData(
                    workDataOf(
                        RecordWorker.CHANNEL_ID to channelId,
                        RecordWorker.TITLE to title,
                        RecordWorker.UNTIL to slot.until,
                    ),
                ).build()
        work.enqueueUniqueWork(NAME + key, ExistingWorkPolicy.REPLACE, request)
        return null
    }

    /** Cancels one; a recording already running stops and keeps what it saved. */
    fun cancel(
        channelId: Long,
        start: Long,
    ) {
        work.cancelUniqueWork(NAME + key(channelId, start))
    }

    /** Switching playlists or signing out: the channels they point at are gone. */
    fun cancelAll() {
        work.cancelAllWorkByTag(TAG)
    }

    private fun slot(info: WorkInfo): RecordingWindow.Slot? = info.tags.firstNotNullOfOrNull(RecordingWindow::fromTag)

    companion object {
        private const val TAG = "recording"
        private const val NAME = "recording:"

        fun key(
            channelId: Long,
            start: Long,
        ): String = "$channelId@$start"
    }
}

/**
 * Records one programme. Watching comes first: while anything plays it waits, and when the player opens (or a TV starts
 * playing) during the recording it hangs up at once and keeps what it saved. While it records, downloads and channel
 * scans wait for it ([com.tunedplayer.app.AppContainer.recordingOpen]), as they do for the player.
 */
class RecordWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = logFailures(TAG, failed = { Result.failure() }) { record() }

    private suspend fun record(): Result {
        val c = applicationContext.container
        val channelId = inputData.getLong(CHANNEL_ID, 0)
        val title = inputData.getString(TITLE).orEmpty()
        val until = inputData.getLong(UNTIL, 0)
        val item = c.db.items().get(channelId) ?: return Result.success() // the channel left the playlist
        attempt(TAG, "Couldn't run as a foreground service") { setForeground(foregroundInfo(title.ifBlank { item.name })) }
        while (c.watching()) {
            if (System.currentTimeMillis() >= until) return Result.success()
            delay(WAIT_MS)
        }
        if (System.currentTimeMillis() >= until || c.recordings.freeBytes() < MIN_FREE_BYTES) return Result.success()
        c.recordingOpen.value = true
        try {
            // A download hangs up first, so the provider never sees two connections.
            c.offline.pause()
            withTimeoutOrNull(DOWNLOAD_STOP_MS) { c.downloadOpen.first { !it } }
            val details =
                Recordings.Recording(
                    id = System.currentTimeMillis(),
                    channelId = item.id,
                    channel = item.name,
                    title = title.ifBlank { null },
                    logo = item.logo,
                )
            val link = StreamLink.split(item.url)
            val portal = Stalker.isLink(link.url)
            val url = if (portal) c.stalker.resolve(link.url) else link.url
            val agent =
                if (portal) {
                    Stalker.USER_AGENT
                } else {
                    link.headers[StreamLink.USER_AGENT] ?: c.settings.settings
                        .first()
                        .userAgent
                }
            val bytes = capture(url, agent, until, details)
            c.recordings.finish(details.copy(durationMs = System.currentTimeMillis() - details.id, bytes = bytes))
        } finally {
            c.recordingOpen.value = false
            // The server takes a while to stop counting the connection; see [AccountRepository.noStreamSince].
            c.account.noStreamSince = System.currentTimeMillis()
            attempt(TAG, "Couldn't start the downloads again") { c.offline.start() }
        }
        return Result.success()
    }

    /** Receives the channel into the recording's file until [until], or until something starts playing. */
    private suspend fun capture(
        url: String,
        agent: String,
        until: Long,
        details: Recordings.Recording,
    ): Long = coroutineScope {
        val c = applicationContext.container
        val capture = LiveCapture(c.http)
        // Hangs up the moment the player opens or a TV plays, or when the recording is cancelled.
        val stopper =
            launch {
                try {
                    combine(c.playerOpen, c.tvCasting.active) { open, tv -> open || tv }.first { it }
                } finally {
                    capture.cancel()
                }
            }
        var bytes = 0L
        var sinceCheck = 0L
        var full = false
        try {
            withContext(Dispatchers.IO) {
                val file = c.recordings.begin(details)
                BufferedOutputStream(FileOutputStream(file), BUFFER).use { out ->
                    val end =
                        capture.capture(url, agent, until, keepGoing = { !full }) { data, offset, length ->
                            out.write(data, offset, length)
                            bytes += length
                            sinceCheck += length
                            if (sinceCheck >= CHECK_EVERY) {
                                sinceCheck = 0
                                full = c.recordings.freeBytes() < MIN_FREE_BYTES
                            }
                        }
                    if (end == LiveCapture.End.UNSUPPORTED) AppLog.w(TAG, "This channel's stream can't be recorded")
                }
            }
        } catch (e: IOException) {
            AppLog.w(TAG, "Couldn't write the recording", e)
        } catch (e: IllegalArgumentException) {
            AppLog.w(TAG, "Not a stream address that can be recorded", e)
        } finally {
            stopper.cancel()
        }
        bytes
    }

    private fun foregroundInfo(what: String): ForegroundInfo {
        val notification = notification(what)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(what: String): Notification {
        val context = applicationContext
        NotificationChannels.ensure(context)
        return NotificationCompat
            .Builder(context, NotificationChannels.DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_tuned)
            .setContentTitle(context.getString(R.string.recording_running, what))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "channel"
        const val TITLE = "title"
        const val UNTIL = "until"
        private const val TAG = "Recording"
        private const val NOTIFICATION_ID = 14
        private const val WAIT_MS = 15_000L
        private const val DOWNLOAD_STOP_MS = 5_000L
        private const val BUFFER = 256 * 1024
        private const val MIN_FREE_BYTES = 500L * 1024 * 1024
        private const val CHECK_EVERY = 16L * 1024 * 1024
    }
}
