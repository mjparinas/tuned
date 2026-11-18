package app.tuned.ui.events

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.Events
import app.tuned.data.EventsRepository
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.common.BackTopBar
import app.tuned.ui.common.focusRing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.Date

/** The page's lists: what the person follows, what's on now, later today and tomorrow. */
data class EventSections(
    val following: List<Events.Event> = emptyList(),
    val onNow: List<Events.Event> = emptyList(),
    val later: List<Events.Event> = emptyList(),
    val tomorrow: List<Events.Event> = emptyList(),
) {
    val empty: Boolean get() = following.isEmpty() && onNow.isEmpty() && later.isEmpty() && tomorrow.isEmpty()
}

class EventsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val events = app.container.events
    private val settings = app.container.settings

    /** null while the first look through the guide runs. */
    val sections = MutableStateFlow<EventSections?>(null)

    val followed: StateFlow<Set<String>> =
        settings.settings
            .map { it.followedEvents }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Keys ([EventsRepository.alertKey]) of alerts still to go off. */
    val alerts: StateFlow<Set<String>> =
        app.container.reminders.scheduled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        viewModelScope.launchSafely(TAG) {
            followed.collect { attempt(TAG, "Couldn't read the guide") { reload() } }
        }
        // Events start and end while the page is open: move them between sections once a minute.
        viewModelScope.launchSafely(TAG) {
            while (true) {
                delay(REFRESH_MS)
                attempt(TAG, "Couldn't read the guide") { reload() }
            }
        }
    }

    private suspend fun reload() {
        val now = System.currentTimeMillis()
        val all = events.events(now)
        val follows = followed.value
        val (mine, rest) = all.partition { follows.isNotEmpty() && Events.follows(it, follows) }
        val midnight = midnightAfter(now)
        sections.value =
            EventSections(
                following = mine,
                onNow = rest.filter { it.liveAt(now) },
                later = rest.filter { !it.liveAt(now) && it.start < midnight },
                tomorrow = rest.filter { it.start >= midnight },
            )
    }

    fun follow(name: String) {
        viewModelScope.launchSafely(TAG) { events.follow(name) }
    }

    fun unfollow(name: String) {
        viewModelScope.launchSafely(TAG) { events.unfollow(name) }
    }

    fun toggleAlert(event: Events.Event) {
        val on = EventsRepository.alertKey(event) !in alerts.value
        viewModelScope.launchSafely(TAG) { events.setAlert(event, on) }
    }

    private companion object {
        const val TAG = "Events"
        const val REFRESH_MS = 60_000L

        fun midnightAfter(now: Long): Long = Calendar
            .getInstance()
            .apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
    }
}

/** An event can be tuned from this long before it starts (pre-shows and early copies often begin early). */
private const val TUNE_BEFORE_MS = 60 * 60_000L

/**
 * Live Events: today's games, fights and pay-per-views from the guide, so one is a tap away instead of a search through
 * dozens of "PPV" channels. Tune hands the title to auto-tune; the bell sets an alert before it starts.
 */
@Composable
fun EventsScreen(
    onTune: (String) -> Unit,
    onBack: () -> Unit,
    vm: EventsViewModel = viewModel(),
) {
    val sections by vm.sections.collectAsStateWithLifecycle()
    val followed by vm.followed.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val toggleAlert = rememberAlertToggle(vm::toggleAlert)
    val now = System.currentTimeMillis()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.events_title), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "follow") { FollowRow(followed, vm::follow, vm::unfollow) }
            val s = sections
            when {
                s == null -> {
                    item(key = "loading") { Loading() }
                }

                s.empty -> {
                    item(key = "empty") { Empty() }
                }

                else -> {
                    val row = EventRowActions(now, alerts, onTune, toggleAlert)
                    section(R.string.events_following, s.following, row)
                    section(R.string.events_on_now, s.onNow, row)
                    section(R.string.events_later, s.later, row)
                    section(R.string.events_tomorrow, s.tomorrow, row)
                }
            }
        }
    }
}

/** On Android 13+, asks once for notifications the first time an alert is set; a "no" just leaves it unset. */
@Composable
private fun rememberAlertToggle(toggle: (Events.Event) -> Unit): (Events.Event) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Events.Event?>(null) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending?.takeIf { granted }?.let(toggle)
            pending = null
        }
    return { event ->
        val needsPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pending = event
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            toggle(event)
        }
    }
}

private class EventRowActions(
    val now: Long,
    val alerts: Set<String>,
    val onTune: (String) -> Unit,
    val toggleAlert: (Events.Event) -> Unit,
)

private fun LazyListScope.section(
    title: Int,
    events: List<Events.Event>,
    row: EventRowActions,
) {
    if (events.isEmpty()) return
    item(key = "h$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(events, key = { "$title:${it.key}@${it.start}" }) { EventRow(it, row) }
}

@Composable
private fun EventRow(
    event: Events.Event,
    row: EventRowActions,
) {
    val context = LocalContext.current
    val live = event.liveAt(row.now)
    val tunable = live || event.start - row.now <= TUNE_BEFORE_MS
    val time =
        if (live) {
            stringResource(R.string.events_live)
        } else if (DateUtils.isToday(event.start)) {
            DateFormat.getTimeFormat(context).format(Date(event.start))
        } else {
            DateUtils.formatDateTime(context, event.start, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRing(RectangleShape, MaterialTheme.colorScheme.primary)
                .clickable { if (tunable) row.onTune(event.title) else row.toggleAlert(event) }
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            time,
            style = MaterialTheme.typography.bodyMedium,
            color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(event.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                pluralStringResource(R.plurals.events_channels, event.channels.size, event.channels.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (tunable) {
            IconButton(onClick = { row.onTune(event.title) }) {
                Icon(Icons.Filled.Radar, stringResource(R.string.tune_button), tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            val on = EventsRepository.alertKey(event) in row.alerts
            IconButton(onClick = { row.toggleAlert(event) }) {
                Icon(
                    if (on) Icons.Filled.Notifications else Icons.Filled.NotificationsNone,
                    contentDescription = stringResource(if (on) R.string.reminder_cancel else R.string.remind_me),
                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Follow a team, league or fighter: their events come first, with an alert before each. */
@Composable
private fun FollowRow(
    followed: Set<String>,
    onFollow: (String) -> Unit,
    onUnfollow: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val add = {
        onFollow(text)
        text = ""
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.events_follow_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = {
                if (text.isNotBlank()) {
                    IconButton(onClick = add) { Icon(Icons.Filled.Add, stringResource(R.string.events_follow)) }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (followed.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(followed.sorted(), key = { it }) { name ->
                    InputChip(
                        selected = true,
                        onClick = { onUnfollow(name) },
                        label = { Text(name) },
                        trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.events_unfollow, name)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
}

@Composable
private fun Empty() {
    Text(
        stringResource(R.string.events_empty),
        modifier = Modifier.padding(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
