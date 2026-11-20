package com.tunedplayer.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import com.tunedplayer.app.R
import com.tunedplayer.app.diagnostics.attempt

/**
 * Every notification channel Tuned posts on, and which of them put a dot (or a count) on the app icon. Only something
 * the person would want to come back for badges the icon: a programme reminder or a live event alert, and a new
 * release. Progress that is on screen only while it runs (refresh, channel scan, downloads, the media notification)
 * never does, or the icon would wear a dot every night after the background refresh (Melvin, 2026-11-19).
 *
 * Android fixes a channel's badge choice when the channel is first made, so the quiet channels got new ids when this
 * was decided; [ensure] deletes the old ones, which badged.
 */
object NotificationChannels {
    const val REMINDERS = "reminders"
    const val UPDATES = "updates"
    const val SYNC = "sync_quiet"
    const val SCAN = "scan_quiet"
    const val DOWNLOADS = "downloads_quiet"
    const val PLAYBACK = "playback"

    data class Spec(
        val id: String,
        @StringRes val name: Int,
        val importance: Int,
        /** Whether this channel's notifications put a dot or count on the launcher icon. */
        val badge: Boolean,
    )

    val ALL: List<Spec> =
        listOf(
            Spec(REMINDERS, R.string.reminder_channel, NotificationManager.IMPORTANCE_HIGH, badge = true),
            Spec(UPDATES, R.string.update_channel, NotificationManager.IMPORTANCE_DEFAULT, badge = true),
            Spec(SYNC, R.string.sync_channel, NotificationManager.IMPORTANCE_MIN, badge = false),
            Spec(SCAN, R.string.scan_channel, NotificationManager.IMPORTANCE_MIN, badge = false),
            Spec(DOWNLOADS, R.string.downloads, NotificationManager.IMPORTANCE_LOW, badge = false),
            Spec(PLAYBACK, R.string.playback_channel, NotificationManager.IMPORTANCE_LOW, badge = false),
        )

    /** Channels made before the badge rule, which Android keeps badging; deleted so they leave system settings. */
    val RETIRED: List<String> = listOf("sync", "scan", "downloads", "default_channel_id")

    @Volatile private var made = false

    /** Makes every channel once per process; cheap, and called just before the first notification, not at start-up. */
    fun ensure(context: Context) {
        if (made) return
        attempt(TAG, "Couldn't set up notification channels") {
            val manager = context.getSystemService(NotificationManager::class.java)
            RETIRED.forEach { manager.deleteNotificationChannel(it) }
            manager.createNotificationChannels(
                ALL.map { spec ->
                    NotificationChannel(spec.id, context.getString(spec.name), spec.importance).apply { setShowBadge(spec.badge) }
                },
            )
            made = true
        }
    }

    private const val TAG = "Notifications"
}
