package app.tuned.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tuned.R
import app.tuned.data.AppSettings
import app.tuned.data.StartTab
import app.tuned.ui.FavouriteOrderRoute
import app.tuned.ui.HiddenGroupsRoute
import app.tuned.ui.RegionSettingsRoute

@Composable
internal fun ChannelsPage(
    settings: AppSettings,
    vm: SettingsViewModel,
    onOpen: (Any) -> Unit,
) {
    ChoiceRow(
        stringResource(R.string.settings_start_tab),
        StartTab.entries,
        settings.startTab,
        label = { stringResource(startTabLabel(it)) },
    ) { t -> vm.update { it.copy(startTab = t) } }
    SwitchRow(
        stringResource(R.string.settings_start_on_last_channel),
        settings.startOnLastChannel,
        stringResource(R.string.settings_start_on_last_channel_summary),
    ) { on -> vm.update { it.copy(startOnLastChannel = on) } }
    SwitchRow(
        stringResource(R.string.settings_guide_in_list),
        settings.showGuideInList,
    ) { on -> vm.update { it.copy(showGuideInList = on) } }
    SwitchRow(
        stringResource(R.string.settings_live_grid),
        settings.liveGrid,
        summary = stringResource(R.string.settings_live_grid_summary),
    ) { on -> vm.update { it.copy(liveGrid = on) } }
    SwitchRow(
        stringResource(R.string.settings_channel_numbers),
        settings.channelNumbers,
        stringResource(R.string.settings_channel_numbers_summary),
    ) { on -> vm.update { it.copy(channelNumbers = on) } }
    SettingRow(
        stringResource(R.string.settings_favourite_order),
        stringResource(R.string.settings_favourite_order_summary),
        onClick = { onOpen(FavouriteOrderRoute) },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
    SectionHeader(stringResource(R.string.settings_header_hidden))
    SettingRow(
        stringResource(R.string.settings_countries),
        if (settings.hiddenRegions.isEmpty()) {
            stringResource(R.string.settings_countries_none_hidden)
        } else {
            pluralStringResource(R.plurals.countries_hidden, settings.hiddenRegions.size, settings.hiddenRegions.size)
        },
        onClick = { onOpen(RegionSettingsRoute) },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
    SettingRow(
        stringResource(R.string.settings_hidden_groups),
        if (settings.hiddenGroups.isEmpty()) {
            stringResource(R.string.settings_hidden_groups_none)
        } else {
            pluralStringResource(R.plurals.groups_hidden, settings.hiddenGroups.size, settings.hiddenGroups.size)
        },
        onClick = { onOpen(HiddenGroupsRoute) },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
    SwitchRow(
        stringResource(R.string.settings_hide_247),
        settings.hide247,
        stringResource(R.string.settings_hide_247_summary),
    ) { on -> vm.update { it.copy(hide247 = on) } }
}

private fun startTabLabel(tab: StartTab): Int = when (tab) {
    StartTab.LIVE -> R.string.tab_live
    StartTab.MOVIES -> R.string.tab_movies
    StartTab.SHOWS -> R.string.tab_shows
    StartTab.LAST -> R.string.start_tab_last
}
