package com.tunedplayer.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.tunedplayer.app.R
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.ui.ChannelHealthRoute

@Composable
internal fun ReliabilityPage(
    settings: AppSettings,
    vm: SettingsViewModel,
    onOpen: (Any) -> Unit,
) {
    SwitchRow(
        stringResource(R.string.settings_reconnect),
        settings.autoReconnect,
        stringResource(R.string.settings_reconnect_summary),
    ) { on -> vm.update { it.copy(autoReconnect = on) } }
    SwitchRow(
        stringResource(R.string.settings_backup_copies),
        settings.backupCopies,
        stringResource(R.string.settings_backup_copies_summary),
    ) { on -> vm.update { it.copy(backupCopies = on) } }
    SettingRow(
        stringResource(R.string.health_title),
        stringResource(R.string.settings_channel_health_summary),
        onClick = { onOpen(ChannelHealthRoute) },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
    SectionHeader(stringResource(R.string.settings_tune))
    ChoiceRow(
        stringResource(R.string.settings_tune_timeout),
        listOf(5_000, 8_000, 10_000, 15_000, 20_000),
        settings.tuneTimeoutMs,
        label = { ms -> pluralStringResource(R.plurals.seconds, ms / 1000, ms / 1000) },
    ) { ms -> vm.update { it.copy(tuneTimeoutMs = ms) } }
    ChoiceRow(
        stringResource(R.string.settings_tune_quality),
        listOf(0, 540, 720, 1080),
        settings.tuneMinHeight,
        label = { h ->
            if (h ==
                0
            ) {
                stringResource(R.string.tune_quality_any)
            } else {
                stringResource(R.string.tune_quality_at_least, h)
            }
        },
    ) { h -> vm.update { it.copy(tuneMinHeight = h) } }
}
