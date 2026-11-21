package com.tunedplayer.app.updates

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.ui.MainActivity
import com.tunedplayer.app.work.NotificationChannels
import kotlinx.coroutines.flow.first

/**
 * Looks for a new release from the background refresh, so Tuned says so even when it isn't open: a notification
 * that opens the app, where the banner installs it with one tap.
 */
object UpdateNotice {
    private const val CHANNEL = NotificationChannels.UPDATES
    private const val NOTIFICATION_ID = 9

    suspend fun checkInBackground(context: Context) {
        val container = context.container
        val updates = container.updates
        if (updates.fromPlay() ||
            !container.settings.settings
                .first()
                .checkUpdates
        ) {
            return
        }
        val already = updates.state.value.pending != null
        if (updates.check() != GitHubUpdates.Outcome.READY || already) return
        val ready = updates.state.value.pending ?: return
        notify(context, ready.versionName)
    }

    /**
     * Opening Tuned clears the notification and its dot on the app icon: the banner inside the app says the same and
     * installs it, so the icon doesn't keep a dot for news the person has already seen.
     */
    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun notify(
        context: Context,
        versionName: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationChannels.ensure(context)
        val open =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_tuned)
                .setContentTitle(context.getString(R.string.update_ready, versionName))
                .setContentText(context.getString(R.string.update_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
