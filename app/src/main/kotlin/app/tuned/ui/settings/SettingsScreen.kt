package app.tuned.ui.settings

import android.app.Application
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.BuildConfig
import app.tuned.R
import app.tuned.container
import app.tuned.data.Accent
import app.tuned.data.AppSettings
import app.tuned.data.BufferMode
import app.tuned.data.StartTab
import app.tuned.data.ThemeMode
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.common.BackTopBar
import app.tuned.ui.theme.swatch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(
    app: Application,
) : AndroidViewModel(app),
    PinActions {
    private val container = app.container

    fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launchSafely("Settings") { container.settings.update(change) }
    }

    val hasPin = container.settings.hasAdultPin.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** How many profiles there are, for the Profiles row. */
    val profileCount =
        container.profiles.profiles
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    override suspend fun setPin(pin: String) = container.settings.setAdultPin(pin)

    override suspend fun unlock(pin: String): PinResult {
        val throttle = container.profiles.throttle
        if (throttle.waiting()) return PinResult.TooMany
        return throttle.record(container.settings.unlockAdult(pin)).toPinResult()
    }

    fun lockAdult() = container.settings.lockAdult()

    override fun matchesLogin(secret: String): Boolean = container.credentials.matches(secret)

    fun refreshNow() = container.scheduler.refreshNow()

    fun clearHistory() {
        viewModelScope.launchSafely("Settings") { container.db.library().clearHistory() }
    }

    /** Signing out forgets the login, the cached playlist and guide, and stops background refreshes. */
    fun signOut(onDone: () -> Unit) {
        viewModelScope.launchSafely("Settings") {
            container.signOut()
            onDone()
        }
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenCaptions: () -> Unit,
    onOpenRegions: () -> Unit,
    onOpenHiddenGroups: () -> Unit,
    onOpenChannelHealth: () -> Unit,
    onOpenFavouriteOrder: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenLogs: () -> Unit,
    onSignedOut: () -> Unit,
    onOpenPlaylists: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    val hours = listOf(0, 3, 6, 12, 24, 48)
    val hoursLabel: @Composable (Int) -> String = { h ->
        if (h == 0) stringResource(R.string.refresh_off) else pluralStringResource(R.plurals.refresh_every_hours, h, h)
    }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings), onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            SectionHeader(stringResource(R.string.settings_account))
            PlaylistsRow(onOpen = onOpenPlaylists)
            AccountStatusRows()
            SettingRow(
                stringResource(R.string.settings_refresh_now),
                lastSync(settings),
                onClick = vm::refreshNow,
            )
            SwitchRow(
                stringResource(R.string.settings_pull_to_refresh),
                settings.pullToRefresh,
                stringResource(R.string.settings_pull_to_refresh_summary),
            ) { on -> vm.update { it.copy(pullToRefresh = on) } }
            BackupRows()
            SettingRow(stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_summary), onClick = {
                confirmSignOut =
                    true
            })

            SectionHeader(stringResource(R.string.settings_content))
            val profileCount by vm.profileCount.collectAsStateWithLifecycle()
            SettingRow(
                stringResource(R.string.settings_profiles),
                if (profileCount > 1) {
                    pluralStringResource(R.plurals.profiles_count, profileCount, profileCount)
                } else {
                    stringResource(R.string.settings_profiles_summary_one)
                },
                onClick = onOpenProfiles,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
            SettingRow(
                stringResource(R.string.settings_countries),
                if (settings.hiddenRegions.isEmpty()) {
                    stringResource(R.string.settings_countries_none_hidden)
                } else {
                    pluralStringResource(R.plurals.countries_hidden, settings.hiddenRegions.size, settings.hiddenRegions.size)
                },
                onClick = onOpenRegions,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
            SettingRow(
                stringResource(R.string.settings_hidden_groups),
                if (settings.hiddenGroups.isEmpty()) {
                    stringResource(R.string.settings_hidden_groups_none)
                } else {
                    pluralStringResource(R.plurals.groups_hidden, settings.hiddenGroups.size, settings.hiddenGroups.size)
                },
                onClick = onOpenHiddenGroups,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
            SettingRow(
                stringResource(R.string.health_title),
                stringResource(R.string.settings_channel_health_summary),
                onClick = onOpenChannelHealth,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
            SettingRow(
                stringResource(R.string.downloads),
                stringResource(R.string.downloads_summary),
                onClick = onOpenDownloads,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
            SettingRow(
                stringResource(R.string.settings_favourite_order),
                stringResource(R.string.settings_favourite_order_summary),
                onClick = onOpenFavouriteOrder,
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
            SwitchRow(
                stringResource(R.string.settings_hide_247),
                settings.hide247,
                stringResource(R.string.settings_hide_247_summary),
            ) { on -> vm.update { it.copy(hide247 = on) } }
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

            SectionHeader(stringResource(R.string.settings_playback))
            SettingRow(
                stringResource(R.string.settings_captions),
                stringResource(if (settings.captions.enabled) R.string.captions_on else R.string.captions_off),
                onClick = onOpenCaptions,
                trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            )
            ChoiceRow(
                stringResource(R.string.settings_audio_language),
                languageCodes,
                settings.audioLanguage,
                label = { languageName(it) },
            ) { l ->
                vm.update { it.copy(audioLanguage = l) }
            }
            ChoiceRow(
                stringResource(R.string.settings_buffer),
                BufferMode.entries,
                settings.buffer,
                label = { stringResource(bufferLabel(it)) },
            ) { b ->
                vm.update { it.copy(buffer = b) }
            }
            SwitchRow(
                stringResource(R.string.settings_resume),
                settings.resumePlayback,
            ) { on -> vm.update { it.copy(resumePlayback = on) } }
            SwitchRow(stringResource(R.string.settings_autoplay), settings.autoPlayNext) { on -> vm.update { it.copy(autoPlayNext = on) } }
            SwitchRow(
                stringResource(R.string.settings_pip),
                settings.pictureInPicture,
                stringResource(R.string.settings_pip_summary),
            ) { on ->
                vm.update { it.copy(pictureInPicture = on) }
            }
            SwitchRow(
                stringResource(R.string.settings_gestures),
                settings.playerGestures,
                stringResource(R.string.settings_gestures_summary),
            ) { on -> vm.update { it.copy(playerGestures = on) } }
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
            SwitchRow(
                stringResource(R.string.settings_match_frame_rate),
                settings.matchFrameRate,
                stringResource(R.string.settings_match_frame_rate_summary),
            ) { on -> vm.update { it.copy(matchFrameRate = on) } }
            ChoiceRow(
                stringResource(R.string.settings_live_rewind),
                listOf(0, 15, 30, 60),
                settings.liveRewindMinutes,
                label = { m ->
                    if (m == 0) {
                        stringResource(R.string.settings_live_rewind_off)
                    } else {
                        pluralStringResource(R.plurals.settings_live_rewind_minutes, m, m)
                    }
                },
            ) { m -> vm.update { it.copy(liveRewindMinutes = m) } }
            SwitchRow(
                stringResource(R.string.settings_landscape),
                settings.landscapePlayback,
            ) { on -> vm.update { it.copy(landscapePlayback = on) } }
            ChoiceRow(
                stringResource(R.string.settings_controls_timeout),
                listOf(2_000, 3_000, 5_000, 8_000),
                settings.controlsTimeoutMs,
                label = { ms -> pluralStringResource(R.plurals.seconds, ms / 1000, ms / 1000) },
            ) { ms -> vm.update { it.copy(controlsTimeoutMs = ms) } }
            ChoiceRow(
                stringResource(R.string.settings_seek_back),
                listOf(5_000L, 10_000L, 15_000L, 30_000L),
                settings.seekBackMs,
                label = { ms -> pluralStringResource(R.plurals.seconds, (ms / 1000).toInt(), (ms / 1000).toInt()) },
            ) { ms -> vm.update { it.copy(seekBackMs = ms) } }
            ChoiceRow(
                stringResource(R.string.settings_seek_forward),
                listOf(10_000L, 15_000L, 30_000L, 60_000L),
                settings.seekForwardMs,
                label = { ms -> pluralStringResource(R.plurals.seconds, (ms / 1000).toInt(), (ms / 1000).toInt()) },
            ) { ms -> vm.update { it.copy(seekForwardMs = ms) } }
            ChoiceRow(
                stringResource(R.string.settings_skip_intro),
                listOf(0L, 30_000L, 60_000L, 85_000L, 90_000L, 120_000L),
                settings.skipIntroMs,
                label = { ms ->
                    if (ms == 0L) {
                        stringResource(R.string.settings_skip_intro_off)
                    } else {
                        pluralStringResource(R.plurals.seconds, (ms / 1000).toInt(), (ms / 1000).toInt())
                    }
                },
            ) { ms -> vm.update { it.copy(skipIntroMs = ms) } }
            SettingRow(stringResource(R.string.settings_clear_history), onClick = vm::clearHistory)

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

            SectionHeader(stringResource(R.string.settings_appearance))
            ChoiceRow(
                stringResource(R.string.settings_theme),
                ThemeMode.entries,
                settings.theme,
                label = { stringResource(themeLabel(it)) },
            ) { t ->
                vm.update { it.copy(theme = t) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(
                    stringResource(R.string.settings_dynamic_colour),
                    settings.dynamicColour,
                    stringResource(R.string.settings_dynamic_colour_summary),
                ) { on -> vm.update { it.copy(dynamicColour = on) } }
            }
            if (!settings.dynamicColour || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                AccentRow(settings.accent) { a -> vm.update { it.copy(accent = a) } }
            }
            SwitchRow(
                stringResource(R.string.settings_pure_black),
                settings.pureBlack,
                stringResource(R.string.settings_pure_black_summary),
            ) { on ->
                vm.update { it.copy(pureBlack = on) }
            }

            SectionHeader(stringResource(R.string.settings_ratings))
            var editTmdb by remember { mutableStateOf(false) }
            SettingRow(
                stringResource(R.string.settings_tmdb_key),
                if (settings.tmdbKey.isBlank()) {
                    stringResource(R.string.settings_tmdb_key_summary)
                } else {
                    stringResource(R.string.settings_tmdb_key_set)
                },
                onClick = { editTmdb = true },
            )
            if (editTmdb) {
                TextDialog(stringResource(R.string.settings_tmdb_key), settings.tmdbKey, onDismiss = { editTmdb = false }) { key ->
                    vm.update { it.copy(tmdbKey = key.trim()) }
                }
            }

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
            SwitchRow(
                stringResource(R.string.settings_check_updates),
                settings.checkUpdates,
                stringResource(R.string.settings_check_updates_summary),
            ) { on -> vm.update { it.copy(checkUpdates = on) } }
            UpdateCheckRow()

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
                    onClick = onOpenLogs,
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )
            }
            HorizontalDivider(Modifier.padding(top = 16.dp))
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out)) },
            text = { Text(stringResource(R.string.settings_sign_out_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    vm.signOut(onSignedOut)
                }) { Text(stringResource(R.string.settings_sign_out)) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun TextDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(onClick = {
                onSave(text)
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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

private fun startTabLabel(tab: StartTab): Int = when (tab) {
    StartTab.LIVE -> R.string.tab_live
    StartTab.MOVIES -> R.string.tab_movies
    StartTab.SHOWS -> R.string.tab_shows
    StartTab.LAST -> R.string.start_tab_last
}

private fun bufferLabel(mode: BufferMode): Int = when (mode) {
    BufferMode.FAST_START -> R.string.buffer_fast
    BufferMode.BALANCED -> R.string.buffer_balanced
    BufferMode.SMOOTH -> R.string.buffer_smooth
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

/** Accent colour: a row of round swatches, the chosen one ticked. */
@Composable
private fun AccentRow(
    selected: Accent,
    onPick: (Accent) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Accent.entries.forEach { accent ->
                val name = stringResource(accentLabel(accent))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(accent.swatch())
                            .selectable(selected = accent == selected, role = Role.RadioButton, onClick = { onPick(accent) })
                            .semantics { contentDescription = name },
                ) {
                    if (accent == selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

private fun accentLabel(accent: Accent): Int = when (accent) {
    Accent.VIOLET -> R.string.accent_violet
    Accent.BLUE -> R.string.accent_blue
    Accent.TEAL -> R.string.accent_teal
    Accent.GREEN -> R.string.accent_green
    Accent.ORANGE -> R.string.accent_orange
    Accent.PINK -> R.string.accent_pink
    Accent.RED -> R.string.accent_red
}
