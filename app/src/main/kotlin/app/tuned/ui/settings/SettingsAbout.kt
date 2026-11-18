package app.tuned.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.tuned.R
import app.tuned.data.AppSettings
import app.tuned.ui.DeveloperLogRoute

@Composable
internal fun AboutPage(
    settings: AppSettings,
    vm: SettingsViewModel,
    onOpen: (Any) -> Unit,
) {
    SwitchRow(
        stringResource(R.string.settings_check_updates),
        settings.checkUpdates,
        stringResource(R.string.settings_check_updates_summary),
    ) { on -> vm.update { it.copy(checkUpdates = on) } }
    UpdateCheckRow()
    SectionHeader(stringResource(R.string.settings_advanced))
    var editUa by remember { mutableStateOf(false) }
    SettingRow(
        stringResource(R.string.settings_user_agent),
        settings.userAgent.ifBlank { stringResource(R.string.settings_user_agent_default) },
        onClick = { editUa = true },
    )
    if (editUa) {
        TextDialog(stringResource(R.string.settings_user_agent), settings.userAgent, onDismiss = { editUa = false }) { ua ->
            vm.update { it.copy(userAgent = ua.trim()) }
        }
    }
    SectionHeader(stringResource(R.string.settings_developer))
    SwitchRow(
        stringResource(R.string.settings_developer_mode),
        settings.developerMode,
        stringResource(R.string.settings_developer_mode_summary),
    ) { on -> vm.update { it.copy(developerMode = on) } }
    if (settings.developerMode) {
        SettingRow(
            stringResource(R.string.logs_title),
            stringResource(R.string.settings_logs_summary),
            onClick = { onOpen(DeveloperLogRoute) },
            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        )
    }
}
