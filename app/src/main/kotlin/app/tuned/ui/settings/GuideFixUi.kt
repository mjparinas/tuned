package app.tuned.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.GuideFixes
import app.tuned.data.AppSettings
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.common.TunedDialog

class GuideFixSettingsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container

    fun setOffset(minutes: Int) {
        viewModelScope.launchSafely("GuideFixSettings") { container.sync.setGuideOffset(minutes) }
    }

    fun extraGuides(): List<String> = container.guideFixes.extraGuides()

    fun setExtraGuides(links: List<String>) = container.guideFixes.setExtraGuides(links)
}

/** Settings › Background: the whole guide's time offset, and extra guide links ([GuideFixes]). */
@Composable
fun GuideFixRows(
    settings: AppSettings,
    vm: GuideFixSettingsViewModel = viewModel(),
) {
    ChoiceRow(
        stringResource(R.string.settings_guide_offset),
        GuideFixes.OFFSETS,
        settings.guideOffsetMinutes,
        label = { m -> if (m == 0) stringResource(R.string.guide_fix_no_offset) else GuideFixes.label(m) },
        onSelect = vm::setOffset,
    )
    var editing by rememberSaveable { mutableStateOf(false) }
    var count by remember { mutableStateOf(vm.extraGuides().size) }
    SettingRow(
        title = stringResource(R.string.settings_extra_guides),
        summary =
            if (count == 0) {
                stringResource(R.string.settings_extra_guides_none)
            } else {
                pluralStringResource(R.plurals.settings_extra_guides_count, count, count)
            },
        onClick = { editing = true },
    )
    if (editing) {
        ExtraGuidesDialog(vm.extraGuides(), onDismiss = { editing = false }) { links ->
            vm.setExtraGuides(links)
            count = links.size
            editing = false
        }
    }
}

@Composable
private fun ExtraGuidesDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val links = remember { initial.toMutableStateList() }
    var text by rememberSaveable { mutableStateOf("") }
    TunedDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_extra_guides)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_extra_guides_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                links.forEachIndexed { i, link ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Only the host shows: the rest of a guide link often holds a key.
                        Text(
                            link.substringAfter("://").substringBefore('/'),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { links.removeAt(i) }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.settings_extra_guides_remove))
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.settings_extra_guides_add)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val all = if (text.startsWith("http://") || text.startsWith("https://")) links + text.trim() else links.toList()
                onSave(all)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
