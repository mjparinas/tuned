package app.tuned.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.tuned.R
import app.tuned.player.PlayerActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * EPG reminders: a notification a few minutes before a programme starts (Settings › Guide › Remind me). Each one is a
 * WorkManager job, so it survives restarts with no table of its own; tapping the notification plays the channel.
 */
class Reminders(
    private val context: Context,
) {
    private val work by lazy { WorkManager.getInstance(context) }

    /** The keys ([key]) of every reminder still waiting. */
    val scheduled: Flow<Set<String>> by lazy {
        work.getWorkInfosByTagFlow(TAG).map { infos ->
            infos
                .filter { it.state == WorkInfo.State.ENQUEUED }
                .flatMap { info -> info.tags.filter { it.startsWith(KEY_TAG) }.map { it.removePrefix(KEY_TAG) } }
                .toSet()
        }
    }

    fun set(
        channelId: Long,
        channelName: String,
        title: String,
        start: Long,
        /** When the programme ends, so its notification (and the app icon's dot) goes away then; 0 when unknown. */
        stop: Long,
        leadMinutes: Int,
        /** For a Live Events alert: tapping it auto-tunes this title instead of opening one channel. */
        tuneQuery: String? = null,
    ) {
        val at = start - TimeUnit.MINUTES.toMillis(leadMinutes.toLong())
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
        val key = key(channelId, start)
        val request =
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .addTag(KEY_TAG + key)
                .setInputData(
                    workDataOf(
                        ReminderWorker.CHANNEL_ID to channelId,
                        ReminderWorker.CHANNEL_NAME to channelName,
                        ReminderWorker.TITLE to title,
                        ReminderWorker.START to start,
                        ReminderWorker.STOP to stop,
                        ReminderWorker.TUNE to tuneQuery,
                    ),
                ).build()
        work.enqueueUniqueWork(NAME + key, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(
        channelId: Long,
        start: Long,
    ) {
        work.cancelUniqueWork(NAME + key(channelId, start))
    }

    /** On sign-out: the channels they point at are gone. */
    fun cancelAll() {
        work.cancelAllWorkByTag(TAG)
    }

    companion object {
        private const val TAG = "reminder"
        private const val KEY_TAG = "reminder-key:"
        private const val NAME = "reminder:"
        private const val UNKNOWN_LENGTH_MS = 3 * 60 * 60_000L
        private const val MIN_SHOWN_MS = 5 * 60_000L

        fun key(
            channelId: Long,
            start: Long,
        ): String = "$channelId@$start"

        /**
         * How long a reminder's notification stays: until the programme ends, or [UNKNOWN_LENGTH_MS] after it starts
         * when the guide gave no end. Never less than [MIN_SHOWN_MS], so a reminder that fires late is still seen.
         */
        fun expiresAfter(
            start: Long,
            stop: Long,
            now: Long,
        ): Long {
            val end = if (stop > start) stop else start + UNKNOWN_LENGTH_MS
            return (end - now).coerceAtLeast(MIN_SHOWN_MS)
        }

        /**
         * One reminder's notification and tap. Two alerts on one channel (two fights on the same pay-per-view channel)
         * once shared a tap keyed by the channel alone, and Android kept only the last one's extras: tapping the first
         * alert tuned the second event.
         */
        fun requestCode(
            channelId: Long,
            start: Long,
        ): Int = key(channelId, start).hashCode()
    }
}

class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val channelId = inputData.getLong(CHANNEL_ID, 0)
        val channelName = inputData.getString(CHANNEL_NAME).orEmpty()
        val title = inputData.getString(TITLE).orEmpty()
        val start = inputData.getLong(START, 0)
        val stop = inputData.getLong(STOP, 0)
        val tune = inputData.getString(TUNE)
        if (start + LATE_MS < System.currentTimeMillis()) return Result.success() // long over; say nothing
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        NotificationChannels.ensure(ctx)
        val open =
            PendingIntent.getActivity(
                ctx,
                Reminders.requestCode(channelId, start),
                if (tune != null) PlayerActivity.tuneIntent(ctx, tune) else PlayerActivity.intent(ctx, channelId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val text = if (tune != null) ctx.getString(R.string.reminder_event_text) else ctx.getString(R.string.reminder_text, channelName)
        val notification =
            NotificationCompat
                .Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_tuned)
                .setContentTitle(title)
                .setContentText(text)
                .setWhen(start)
                .setShowWhen(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                // Once it's over the reminder has nothing left to offer, so it leaves the shade and the icon's dot.
                .setTimeoutAfter(Reminders.expiresAfter(start, stop, System.currentTimeMillis()))
                .build()
        NotificationManagerCompat.from(ctx).notify(Reminders.requestCode(channelId, start), notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "channel_id"
        const val CHANNEL_NAME = "channel_name"
        const val TITLE = "title"
        const val START = "start"
        const val STOP = "stop"
        const val TUNE = "tune"
        private const val CHANNEL = NotificationChannels.REMINDERS
        private const val LATE_MS = 30 * 60_000L
    }
}
