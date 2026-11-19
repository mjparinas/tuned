package app.tuned.data

import app.tuned.core.Events
import app.tuned.data.db.TunedDatabase
import app.tuned.work.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Live Events: today's games, fights and pay-per-views from the guide ([Events] decides what counts), the teams and
 * leagues the person follows, and an alert before a followed event starts. Alerts are ordinary guide reminders whose
 * tap auto-tunes the event, so they need nothing of their own to survive a restart.
 */
class EventsRepository(
    private val db: TunedDatabase,
    private val settings: SettingsRepository,
    private val reminders: Reminders,
) {
    /** Events from now until [AHEAD_MS] ahead: live ones first, then by start. */
    suspend fun events(now: Long = System.currentTimeMillis()): List<Events.Event> {
        val s = settings.settings.first()
        val rows =
            db
                .items()
                .eventProgrammes(now, now + AHEAD_MS, s.hiddenKeys(), s.showAdult)

        // Thousands of guide rows through the title rules: off the main thread.
        return withContext(Dispatchers.Default) {
            val sporty =
                rows
                    .filter { Events.sportyChannel(it.channelName, it.group) }
                    .map { Events.Row(it.itemId, it.channelName, it.group, it.title, it.start, it.stop) }
            Events.group(sporty, now)
        }
    }

    suspend fun follow(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        settings.update { s ->
            if (s.followedEvents.any { it.equals(clean, ignoreCase = true) }) s else s.copy(followedEvents = s.followedEvents + clean)
        }
        scheduleAlerts()
    }

    /** Stops following [name], and takes away the alerts it set that nothing else followed still covers. */
    suspend fun unfollow(
        name: String,
        now: Long = System.currentTimeMillis(),
    ) {
        settings.update { s -> s.copy(followedEvents = s.followedEvents - name) }
        val followed = settings.settings.first().followedEvents
        Events
            .noLongerFollowed(events(now).filter { it.start > now }, name, followed)
            .forEach { setAlert(it, on = false) }
    }

    /** Sets an alert for [event] (or takes it away), [AppSettings.reminderLeadMinutes] before it starts. */
    suspend fun setAlert(
        event: Events.Event,
        on: Boolean,
    ) {
        val channel = event.channels.firstOrNull() ?: return
        if (on) {
            reminders.set(
                channel,
                "",
                event.title,
                event.start,
                event.stop,
                settings.settings.first().reminderLeadMinutes,
                tuneQuery = event.title,
            )
        } else {
            reminders.cancel(channel, event.start)
        }
    }

    /** After each guide refresh: an alert for every upcoming event about something followed. */
    suspend fun scheduleAlerts(now: Long = System.currentTimeMillis()) {
        val followed = settings.settings.first().followedEvents
        if (followed.isEmpty()) return
        events(now)
            .filter { it.start > now && Events.follows(it, followed) }
            .forEach { setAlert(it, on = true) }
    }

    companion object {
        const val AHEAD_MS = 36 * 3_600_000L

        /** The key ([Reminders.key]) of [event]'s alert. */
        fun alertKey(event: Events.Event): String = Reminders.key(event.channels.firstOrNull() ?: 0, event.start)
    }
}
