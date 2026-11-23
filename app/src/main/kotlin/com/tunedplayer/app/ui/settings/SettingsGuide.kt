package com.tunedplayer.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunedplayer.app.R
import com.tunedplayer.app.core.TimeZones
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.diagnostics.DeviceClock
import com.tunedplayer.app.diagnostics.startActivitySafely
import com.tunedplayer.app.ui.SyncText
import com.tunedplayer.app.ui.common.isTelevision
import java.time.ZoneId
import android.provider.Settings as AndroidSettings

@Composable
internal fun GuidePage(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val hours = listOf(0, 3, 6, 12, 24, 48)
    val hoursLabel: @Composable (Int) -> String = { h ->
        if (h == 0) stringResource(R.string.refresh_off) else pluralStringResource(R.plurals.refresh_every_hours, h, h)
    }
    RefreshNowRow(settings, vm)
    ChoiceRow(stringResource(R.string.settings_playlist_refresh), hours, settings.playlistRefreshHours, hoursLabel) { h ->
        vm.update { it.copy(playlistRefreshHours = h) }
    }
    ChoiceRow(stringResource(R.string.settings_guide_refresh), hours, settings.guideRefreshHours, hoursLabel) { h ->
        vm.update { it.copy(guideRefreshHours = h) }
    }
    // Pull to refresh needs a finger; a TV refreshes from Refresh now or the scheduled hours.
    if (!isTelevision()) {
        SwitchRow(
            stringResource(R.string.settings_pull_to_refresh),
            settings.pullToRefresh,
            stringResource(R.string.settings_pull_to_refresh_summary),
        ) { on -> vm.update { it.copy(pullToRefresh = on) } }
    }
    SwitchRow(
        stringResource(R.string.settings_unmetered),
        settings.unmeteredOnly,
        stringResource(R.string.settings_unmetered_summary),
    ) { on ->
        vm.update { it.copy(unmeteredOnly = on) }
    }
    SwitchRow(
        stringResource(R.string.settings_playlist_copies),
        settings.keepPlaylistCopies,
        stringResource(R.string.settings_playlist_copies_summary),
    ) { on -> vm.keepPlaylistCopies(on) }
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
    NotificationsRow()
}

/**
 * Refresh now: while the refresh it started waits or runs, says which step it is on (the same line as the bar above the
 * lists) with a spinner, and then when it last refreshed or what went wrong.
 */
@Composable
private fun RefreshNowRow(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    val pending by vm.refreshPending.collectAsStateWithLifecycle()
    val progress by vm.refreshProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingRow(
        stringResource(R.string.settings_refresh_now),
        progress?.let { SyncText.describe(context, it) }
            ?: if (pending) stringResource(R.string.settings_refresh_starting) else lastSync(settings),
        onClick = vm::refreshNow,
        trailing = if (pending || progress != null) ({ RowSpinner() }) else null,
    )
}

/**
 * Android's own page for Tuned's notifications, where the dot on the app icon, sounds and each kind of alert are
 * switched. Android owns those choices, so Tuned links to them rather than copying them. TVs have no icon dots.
 */
@Composable
private fun NotificationsRow() {
    if (isTelevision()) return
    val context = LocalContext.current
    SettingRow(
        stringResource(R.string.settings_notifications),
        stringResource(R.string.settings_notifications_summary),
        onClick = {
            val page =
                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
            if (!context.startActivitySafely(page, "GuideSettings")) {
                context.startActivitySafely(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    "GuideSettings",
                )
            }
        },
    )
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
        DeviceClock.explain(LocalContext.current, stringResource(R.string.settings_last_error, settings.lastSyncError))
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
