package com.tunedplayer.app.ui.guide

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.GuideFixes
import com.tunedplayer.app.data.db.GuideChannelEntity
import com.tunedplayer.app.data.db.GuideFixEntity
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.common.TunedDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GuideFixViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val fixes = app.container.guideFixes
    private val itemId = MutableStateFlow<Long?>(null)

    val fix: StateFlow<GuideFixEntity?> =
        itemId
            .flatMapLatest { id -> id?.let(fixes::fix) ?: emptyFlow() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val results = MutableStateFlow<List<GuideChannelEntity>>(emptyList())

    fun load(id: Long) {
        itemId.value = id
    }

    private var searching: Job? = null

    fun search(query: String) {
        searching?.cancel()
        searching =
            viewModelScope.launchSafely("GuideFix") {
                delay(SEARCH_DELAY_MS) // typing: wait for a pause
                results.value = if (query.isBlank()) emptyList() else fixes.searchChannels(query)
            }
    }

    suspend fun name(id: String): String? = fixes.channelName(id)

    fun save(
        item: ItemEntity,
        guideId: String?,
        offsetMinutes: Int,
        done: () -> Unit,
    ) {
        viewModelScope.launchSafely("GuideFix") {
            fixes.save(item, guideId, offsetMinutes)
            done()
        }
    }

    fun clear(
        item: ItemEntity,
        done: () -> Unit,
    ) {
        viewModelScope.launchSafely("GuideFix") {
            fixes.clear(item.id)
            done()
        }
    }

    private companion object {
        const val SEARCH_DELAY_MS = 250L
    }
}

/**
 * Fix a channel's guide: move its times, or take its programmes from another guide entry (for a channel the guide
 * didn't match, or matched to the wrong channel). [onSaved] runs once the change is on the device.
 */
@Composable
fun GuideFixDialog(
    channel: ItemEntity,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    vm: GuideFixViewModel = viewModel(),
) {
    LaunchedEffect(channel.id) { vm.load(channel.id) }
    val fix by vm.fix.collectAsStateWithLifecycle()
    var offset by rememberSaveable(fix) { mutableIntStateOf(fix?.offsetMinutes ?: 0) }
    var guideId by rememberSaveable(fix) { mutableStateOf(fix?.guideId) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val done = {
        onSaved()
        onDismiss()
    }
    TunedDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.guide_fix_title)) },
        text = {
            if (picking) {
                GuideEntryPicker(vm) { picked ->
                    guideId = picked
                    picking = false
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OffsetStepper(offset) { offset = it }
                    GuideEntryRow(vm, guideId, onChange = { picking = true }, onReset = { guideId = null })
                    Text(
                        stringResource(R.string.guide_fix_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (!picking) TextButton(onClick = { vm.save(channel, guideId, offset, done) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (fix != null && !picking) {
                    TextButton(onClick = { vm.clear(channel, done) }) { Text(stringResource(R.string.guide_fix_remove)) }
                }
                TextButton(onClick = { if (picking) picking = false else onDismiss() }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Earlier or later, half an hour at a time near zero and then by the hour ([GuideFixes.OFFSETS]). */
@Composable
private fun OffsetStepper(
    minutes: Int,
    onChange: (Int) -> Unit,
) {
    val options = GuideFixes.OFFSETS
    val index = options.indexOf(minutes).takeIf { it >= 0 } ?: options.indexOf(0)
    Column {
        Text(stringResource(R.string.guide_fix_offset), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(options[index - 1]) }, enabled = index > 0) {
                Icon(Icons.Filled.Remove, stringResource(R.string.guide_fix_earlier))
            }
            Text(
                if (minutes == 0) stringResource(R.string.guide_fix_no_offset) else GuideFixes.label(minutes),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onChange(options[index + 1]) }, enabled = index < options.lastIndex) {
                Icon(Icons.Filled.Add, stringResource(R.string.guide_fix_later))
            }
        }
    }
}

@Composable
private fun GuideEntryRow(
    vm: GuideFixViewModel,
    guideId: String?,
    onChange: () -> Unit,
    onReset: () -> Unit,
) {
    var name by remember(guideId) { mutableStateOf<String?>(null) }
    LaunchedEffect(guideId) { name = guideId?.let { vm.name(it) ?: it } }
    Column {
        Text(stringResource(R.string.guide_fix_entry), style = MaterialTheme.typography.titleSmall)
        Text(
            name ?: stringResource(R.string.guide_fix_entry_playlist),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = onChange) { Text(stringResource(R.string.guide_fix_choose)) }
            if (guideId != null) TextButton(onClick = onReset) { Text(stringResource(R.string.guide_fix_entry_reset)) }
        }
    }
}

@Composable
private fun GuideEntryPicker(
    vm: GuideFixViewModel,
    onPick: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by vm.results.collectAsStateWithLifecycle()
    Column {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                vm.search(it)
            },
            label = { Text(stringResource(R.string.guide_fix_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (query.isNotBlank() && results.isEmpty()) {
            Text(
                stringResource(R.string.guide_fix_no_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(results, key = { it.id }) { c ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(c.id) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(c.name, style = MaterialTheme.typography.bodyLarge)
                    Text(c.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
