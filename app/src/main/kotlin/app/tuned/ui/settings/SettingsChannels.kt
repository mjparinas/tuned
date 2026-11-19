package app.tuned.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tuned.R
import app.tuned.core.Regions
import app.tuned.data.AppSettings
import app.tuned.data.StartTab
import app.tuned.diagnostics.attempt
import app.tuned.ui.FavouriteOrderRoute
import app.tuned.ui.HiddenGroupsRoute
import app.tuned.ui.RegionSettingsRoute
import app.tuned.widget.AppShortcuts
import app.tuned.work.TvHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    SwitchRow(
        stringResource(R.string.settings_always_show_scrollbar),
        settings.alwaysShowScrollbar,
        stringResource(R.string.settings_always_show_scrollbar_summary),
    ) { on -> vm.update { it.copy(alwaysShowScrollbar = on) } }
    AppShortcutsRow(settings, vm)
    SettingRow(
        stringResource(R.string.settings_favourite_order),
        stringResource(R.string.settings_favourite_order_summary),
        onClick = { onOpen(FavouriteOrderRoute) },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
    SectionHeader(stringResource(R.string.settings_header_hidden))
    SettingRow(
        stringResource(R.string.settings_countries),
        countriesSummary(settings.hiddenRegions),
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

/** "118 countries hidden", with "and no country" when streams with no country are hidden too. */
@Composable
private fun countriesSummary(hidden: Set<String>): String {
    val countries = (hidden - Regions.NONE).size
    val none = Regions.NONE in hidden
    return when {
        countries == 0 && !none -> stringResource(R.string.settings_countries_none_hidden)
        countries == 0 -> stringResource(R.string.regions_other_hidden)
        none -> pluralStringResource(R.plurals.countries_and_other_hidden, countries, countries)
        else -> pluralStringResource(R.plurals.countries_hidden, countries, countries)
    }
}

/** Search, Live events and the last channel on the app icon's long-press menu ([AppShortcuts]). Phones and tablets only. */
@Composable
private fun AppShortcutsRow(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val context = LocalContext.current
    if (TvHome.isTv(context)) return
    SwitchRow(
        stringResource(R.string.settings_app_shortcuts),
        settings.appShortcuts,
        stringResource(R.string.settings_app_shortcuts_summary),
    ) { on -> vm.update { it.copy(appShortcuts = on) } }
    LaunchedEffect(settings.appShortcuts) {
        withContext(Dispatchers.IO) { attempt("Shortcuts", "Couldn't update the app icon's shortcuts") { AppShortcuts.update(context) } }
    }
}
