package app.tuned.ui.settings

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tuned.R
import app.tuned.core.TimeZones
import app.tuned.data.AppSettings
import java.time.ZoneId

@Composable
internal fun GuidePage(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val hours = listOf(0, 3, 6, 12, 24, 48)
    val hoursLabel: @Composable (Int) -> String = { h ->
        if (h == 0) stringResource(R.string.refresh_off) else pluralStringResource(R.plurals.refresh_every_hours, h, h)
    }
    SettingRow(
        stringResource(R.string.settings_refresh_now),
        lastSync(settings),
        onClick = vm::refreshNow,
    )
    ChoiceRow(stringResource(R.string.settings_playlist_refresh), hours, settings.playlistRefreshHours, hoursLabel) { h ->
        vm.update { it.copy(playlistRefreshHours = h) }
    }
    ChoiceRow(stringResource(R.string.settings_guide_refresh), hours, settings.guideRefreshHours, hoursLabel) { h ->
        vm.update { it.copy(guideRefreshHours = h) }
    }
    SwitchRow(
        stringResource(R.string.settings_pull_to_refresh),
        settings.pullToRefresh,
        stringResource(R.string.settings_pull_to_refresh_summary),
    ) { on -> vm.update { it.copy(pullToRefresh = on) } }
    SwitchRow(
        stringResource(R.string.settings_unmetered),
        settings.unmeteredOnly,
        stringResource(R.string.settings_unmetered_summary),
    ) { on ->
        vm.update { it.copy(unmeteredOnly = on) }
    }
    SectionHeader(stringResource(R.string.settings_header_guide))
    ChoiceRow(
        stringResource(R.string.settings_guide_days),
        listOf(1, 2, 3, 5, 7),
        settings.guideDays,
        label = { d -> pluralStringResource(R.plurals.days, d, d) },
    ) { d -> vm.update { it.copy(guideDays = d) } }
    GuideFixRows(settings)
    TimeZoneRow(settings, vm)
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
}

/** Which zone guide and event times show in; follows the phone unless a zone is picked (Melvin, 2026-11-18). */
@Composable
private fun TimeZoneRow(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val locale = LocalConfiguration.current.locales[0]
    val now = System.currentTimeMillis()
    val choices = listOf(TimeZones.DEVICE) + (TimeZones.CHOICES + settings.timeZone).filter { it.isNotBlank() }.distinct()
    val selected = settings.timeZone.takeIf { it in choices } ?: TimeZones.DEVICE
    ChoiceRow(
        stringResource(R.string.settings_time_zone),
        choices,
        selected,
        label = { id ->
            if (id == TimeZones.DEVICE) {
                stringResource(R.string.settings_time_zone_device, TimeZones.label(ZoneId.systemDefault().id, now, locale))
            } else {
                TimeZones.label(id, now, locale)
            }
        },
    ) { id -> vm.update { it.copy(timeZone = id) } }
}

@Composable
private fun lastSync(settings: AppSettings): String = when {
    settings.lastSyncError.isNotBlank() -> {
        stringResource(R.string.settings_last_error, settings.lastSyncError)
    }

    settings.lastPlaylistSync == 0L -> {
        stringResource(R.string.settings_never_synced)
    }

    else -> {
        stringResource(
            R.string.settings_last_synced,
            DateUtils
                .getRelativeTimeSpanString(
                    settings.lastPlaylistSync,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
        )
    }
}
