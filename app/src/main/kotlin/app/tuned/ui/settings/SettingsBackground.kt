package app.tuned.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tuned.R
import app.tuned.data.AppSettings

@Composable
internal fun BackgroundSettings(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val hours = listOf(0, 3, 6, 12, 24, 48)
    val hoursLabel: @Composable (Int) -> String = { h ->
        if (h == 0) stringResource(R.string.refresh_off) else pluralStringResource(R.plurals.refresh_every_hours, h, h)
    }
    SectionHeader(stringResource(R.string.settings_background))
    ChoiceRow(stringResource(R.string.settings_playlist_refresh), hours, settings.playlistRefreshHours, hoursLabel) { h ->
        vm.update { it.copy(playlistRefreshHours = h) }
    }
    ChoiceRow(stringResource(R.string.settings_guide_refresh), hours, settings.guideRefreshHours, hoursLabel) { h ->
        vm.update { it.copy(guideRefreshHours = h) }
    }
    ChoiceRow(
        stringResource(R.string.settings_guide_days),
        listOf(1, 2, 3, 5, 7),
        settings.guideDays,
        label = { d -> pluralStringResource(R.plurals.days, d, d) },
    ) { d -> vm.update { it.copy(guideDays = d) } }
    GuideFixRows(settings)
    ChoiceRow(
        stringResource(R.string.settings_reminder_lead),
        listOf(0, 1, 5, 10, 15, 30),
        settings.reminderLeadMinutes,
        label = { m ->
            if (m ==
                0
            ) {
                stringResource(R.string.reminder_at_start)
            } else {
                pluralStringResource(R.plurals.minutes_before, m, m)
            }
        },
    ) { m -> vm.update { it.copy(reminderLeadMinutes = m) } }
    SwitchRow(
        stringResource(R.string.settings_unmetered),
        settings.unmeteredOnly,
        stringResource(R.string.settings_unmetered_summary),
    ) { on ->
        vm.update { it.copy(unmeteredOnly = on) }
    }
}
