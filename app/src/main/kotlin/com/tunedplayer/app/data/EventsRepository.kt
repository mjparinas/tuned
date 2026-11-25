package com.tunedplayer.app.data

import com.tunedplayer.app.core.ChannelEvents
import com.tunedplayer.app.core.Events
import com.tunedplayer.app.core.TimeZones
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.work.Reminders
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
                .events()
                .eventProgrammes(now, now + AHEAD_MS, s.hiddenKeys(), s.showAdult)
        val channels =
            attempt(TAG, "Couldn't read event channel names") {
                db.events().eventChannels(s.hiddenKeys(), s.showAdult)
            }.getOrDefault(emptyList())

        // Thousands of guide rows through the title rules: off the main thread.
        return withContext(Dispatchers.Default) {
            val sporty =
                rows
                    .filter { Events.sportyChannel(it.channelName, it.group) }
                    .map { Events.Row(it.itemId, it.channelName, it.group, it.title, it.start, it.stop) }
            val fromGuide = Events.group(sporty, now)
            // A channel whose guide lists no event may say it in its name ("PPV 7: UFC 312 10PM ET").
            val listed = sporty.filter { Events.isEvent(it.title) }.mapTo(HashSet()) { it.itemId }
            val unlisted = channels.filter { it.itemId !in listed }.map { ChannelEvents.Channel(it.itemId, it.name) }
            // A name the rules trip over costs only the names, never the guide's events.
            attempt(TAG, "Couldn't read events from channel names") {
                val named = ChannelEvents.find(unlisted, now, now + AHEAD_MS, TimeZones.resolve(s.timeZone))
                Events.withNamed(fromGuide, named, now)
            }.getOrDefault(fromGuide)
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
        private const val TAG = "Events"
        const val AHEAD_MS = 36 * 3_600_000L

        /** The key ([Reminders.key]) of [event]'s alert. */
        fun alertKey(event: Events.Event): String = Reminders.key(event.channels.firstOrNull() ?: 0, event.start)
    }
}
