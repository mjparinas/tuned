package com.tunedplayer.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunedplayer.app.R
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.ui.ProfilesRoute

@Composable
internal fun ProfilesPage(
    settings: AppSettings,
    vm: SettingsViewModel,
    onOpen: (Any) -> Unit,
) {
    val profileCount by vm.profileCount.collectAsStateWithLifecycle()
    SettingRow(
        stringResource(R.string.settings_profiles),
        if (profileCount > 1) {
            pluralStringResource(R.plurals.profiles_count, profileCount, profileCount)
        } else {
            stringResource(R.string.settings_profiles_summary_one)
        },
        onClick = { onOpen(ProfilesRoute()) },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    var pinDialog by remember { mutableStateOf<PinMode?>(null) }
    SwitchRow(
        stringResource(R.string.settings_show_adult),
        settings.showAdult,
        stringResource(if (hasPin) R.string.settings_show_adult_summary else R.string.settings_show_adult_summary_no_pin),
    ) { on ->
        if (on) pinDialog = if (hasPin) PinMode.Unlock else PinMode.Create else vm.lockAdult()
    }
    if (hasPin && settings.showAdult) {
        SettingRow(
            stringResource(R.string.settings_change_pin),
            stringResource(R.string.settings_change_pin_summary),
            onClick = { pinDialog = PinMode.Create },
        )
    }
    pinDialog?.let { mode -> PinDialog(mode, vm, onDone = { pinDialog = null }) }
}
