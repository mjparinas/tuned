package com.tunedplayer.app.ui.guide

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.CatchUp
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.ProgrammeEntity
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.player.PlayerActivity
import com.tunedplayer.app.premium.Premium
import com.tunedplayer.app.premium.premiumUnlocked
import com.tunedplayer.app.ui.common.BackTopBar
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.rememberTimeLabels
import com.tunedplayer.app.work.Reminders
import com.tunedplayer.app.work.ScheduledRecordings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = app.container.db
    val channel = MutableStateFlow<ItemEntity?>(null)

    fun load(id: Long) {
        if (channel.value?.id == id) return
        viewModelScope.launchSafely("Guide") { channel.value = db.items().get(id) }
    }

    /** After a guide fix: the channel's guide id may have changed, so read it again. */
    fun reload() {
        val id = channel.value?.id ?: return
        viewModelScope.launchSafely("Guide") { channel.value = db.items().get(id) }
    }

    /** Days of recordings the channel keeps (catch-up), 0 for none. */
    val catchUpDays: StateFlow<Int> =
        channel
            .filterNotNull()
            .flatMapLatest { c -> db.catchUp().days(CatchUp.key(c.url)).map { it ?: 0 } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** What's on now and next; on a catch-up channel, also what's been on within its recordings. */
    val programmes: StateFlow<List<ProgrammeEntity>> =
        combine(channel.filterNotNull(), catchUpDays) { c, days -> c to days }
            .flatMapLatest { (c, days) ->
                val from = CatchUp.keepFrom(System.currentTimeMillis(), days)
                c.tvgId?.let { db.programmes().upcoming(it, from, if (days > 0) LIMIT_WITH_PAST else LIMIT) } ?: emptyFlow()
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val picks = app.container.picks

    val favourite: StateFlow<Boolean> =
        combine(channel.filterNotNull(), picks.favourites.value) { c, ids -> c.id in ids }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val reminders = app.container.reminders
    private val settings = app.container.settings

    /** Keys ([Reminders.key]) of the reminders waiting to go off. */
    val scheduled: StateFlow<Set<String>> =
        reminders.scheduled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggleReminder(p: ProgrammeEntity) {
        val c = channel.value ?: return
        if (Reminders.key(c.id, p.start) in scheduled.value) {
            reminders.cancel(c.id, p.start)
        } else {
            viewModelScope.launchSafely("Guide") {
                reminders.set(c.id, c.name, p.title, p.start, p.stop, settings.settings.first().reminderLeadMinutes)
            }
        }
    }

    private val recordings = app.container.scheduledRecordings

    /** Keys ([ScheduledRecordings.key]) of the programmes set to record. */
    val recording: StateFlow<Set<String>> =
        recordings.scheduled
            .map { slots -> slots.map { it.key }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Sets or cancels a recording; [onSet] hears whether it was set (false: another one holds the connection then). */
    fun toggleRecording(
        p: ProgrammeEntity,
        onSet: (Boolean) -> Unit,
    ) {
        val c = channel.value ?: return
        if (ScheduledRecordings.key(c.id, p.start) in recording.value) {
            recordings.cancel(c.id, p.start)
        } else {
            viewModelScope.launchSafely("Guide") {
                val padding = settings.settings.first().recordPaddingMinutes
                onSet(recordings.set(c.id, p.title, p.start, p.stop, padding) == null)
            }
        }
    }

    private companion object {
        const val LIMIT = 200
        const val LIMIT_WITH_PAST = 1_000
    }

    fun toggleFavourite() {
        val c = channel.value ?: return
        picks.setFavourite(c.id, !favourite.value)
    }
}

/** A channel's schedule from the downloaded guide: on now first, then what's next, grouped by day. */
@Composable
fun GuideScreen(
    itemId: Long,
    onPlay: (Long) -> Unit,
    onBack: () -> Unit,
    vm: GuideViewModel = viewModel(),
) {
    LaunchedEffect(itemId) { vm.load(itemId) }
    val channel by vm.channel.collectAsStateWithLifecycle()
    val programmes by vm.programmes.collectAsStateWithLifecycle()
    val favourite by vm.favourite.collectAsStateWithLifecycle()
    val catchUpDays by vm.catchUpDays.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val times = rememberTimeLabels()
    val now = System.currentTimeMillis()
    val scheduled by vm.scheduled.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val premium = premiumUnlocked()
    val toggleRecording: (ProgrammeEntity) -> Unit = { p ->
        if (!premium) {
            Premium.open(context)
        } else {
            vm.toggleRecording(p) { set ->
                val message =
                    if (set) {
                        context.getString(
                            R.string.recording_scheduled,
                            p.title,
                        )
                    } else {
                        context.getString(R.string.recording_clash)
                    }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    var pending by remember { mutableStateOf<ProgrammeEntity?>(null) }
    // Android 13+ asks once for notifications the first time a reminder is set; a "no" just leaves it unset.
    val askPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending?.takeIf { granted }?.let(vm::toggleReminder)
            pending = null
        }
    val toggleReminder: (ProgrammeEntity) -> Unit = { p ->
        val needsPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pending = p
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.toggleReminder(p)
        }
    }

    var fixing by remember { mutableStateOf(false) }
    channel?.takeIf { fixing }?.let { c -> GuideFixDialog(c, onDismiss = { fixing = false }, onSaved = vm::reload) }

    Scaffold(
        topBar = {
            BackTopBar(channel?.name.orEmpty(), onBack) {
                IconButton(onClick = { fixing = true }, enabled = channel != null) {
                    Icon(Icons.Filled.EditCalendar, contentDescription = stringResource(R.string.guide_fix_title))
                }
                IconButton(onClick = vm::toggleFavourite) {
                    Icon(
                        if (favourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(if (favourite) R.string.favourite_remove else R.string.favourite_add),
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onPlay(itemId) },
                icon = { Icon(Icons.Filled.PlayArrow, null) },
                text = { Text(stringResource(R.string.watch_now)) },
            )
        },
    ) { padding ->
        if (programmes.isEmpty()) {
            Column(Modifier.padding(padding)) {
                ChannelActions(itemId)
                Text(
                    stringResource(R.string.guide_empty),
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        val rows =
            remember(programmes, times) {
                programmes.mapIndexed { i, p ->
                    val label = times.date(p.start)
                    Triple(p, label, i == 0 || label != times.date(programmes[i - 1].start))
                }
            }
        // With catch-up the list starts in the past, so it opens on what's on now.
        val listState = rememberLazyListState()
        var scrolled by remember { mutableStateOf(false) }
        LaunchedEffect(rows) {
            if (!scrolled) {
                val current = rows.indexOfFirst { it.first.stop > now }
                // One past it, since the channel's buttons are the list's first item.
                if (current > 0) listState.scrollToItem(current + 1)
                scrolled = true
            }
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "actions") { ChannelActions(itemId) }
            items(rows, key = { it.first.rowId }) { (p, label, header) ->
                val onNow = p.start <= now && p.stop > now
                val replay = !onNow && CatchUp.available(p.start, now, catchUpDays)
                val watchAgain = { PlayerActivity.catchUp(context, itemId, p.title, p.start, p.stop) }
                Column {
                    if (header) {
                        Text(
                            stringResource(R.string.grid_day_zone, label, times.zoneName(p.start)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (onNow) {
                                        Modifier.background(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                        )
                                    } else {
                                        Modifier
                                    },
                                ).focusRing(RectangleShape, MaterialTheme.colorScheme.primary)
                                .clickable(enabled = onNow || replay) { if (onNow) onPlay(itemId) else watchAgain() }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            times.time(p.start),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 64.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (onNow) stringResource(R.string.guide_on_now, p.title) else p.title,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            p.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                )
                            }
                        }
                        if (replay) {
                            IconButton(onClick = watchAgain) {
                                Icon(
                                    Icons.Filled.Replay,
                                    contentDescription = stringResource(R.string.catchup_watch),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        // Recording is Premium, as the player's Record button is (docs/decisions/premium.md).
                        if (p.stop > now) {
                            val recorded = channel?.let { ScheduledRecordings.key(it.id, p.start) in recording } == true
                            IconButton(onClick = { toggleRecording(p) }) {
                                Icon(
                                    if (recorded) Icons.Filled.FiberManualRecord else Icons.Filled.RadioButtonUnchecked,
                                    contentDescription =
                                        stringResource(
                                            if (recorded) R.string.recording_cancel else R.string.recording_set,
                                        ),
                                    tint = if (recorded) RECORD_RED else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (p.start > now) {
                            val reminded = channel?.let { Reminders.key(it.id, p.start) in scheduled } == true
                            IconButton(onClick = { toggleReminder(p) }) {
                                Icon(
                                    if (reminded) Icons.Filled.Notifications else Icons.Filled.NotificationsNone,
                                    contentDescription = stringResource(if (reminded) R.string.reminder_cancel else R.string.remind_me),
                                    tint = if (reminded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The same red as the player's REC mark. */
private val RECORD_RED = Color(0xFFE53935)
