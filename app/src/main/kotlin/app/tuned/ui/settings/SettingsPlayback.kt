package app.tuned.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tuned.R
import app.tuned.data.AppSettings
import app.tuned.data.BufferMode
import app.tuned.ui.CaptionSettingsRoute

@Composable
internal fun PlaybackSettings(
    settings: AppSettings,
    vm: SettingsViewModel,
    onOpen: (Any) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_playback))
    SettingRow(
        stringResource(R.string.settings_captions),
        stringResource(if (settings.captions.enabled) R.string.captions_on else R.string.captions_off),
        onClick = { onOpen(CaptionSettingsRoute) },
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
}

@Composable
internal fun TuneSettings(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
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

private fun bufferLabel(mode: BufferMode): Int = when (mode) {
    BufferMode.FAST_START -> R.string.buffer_fast
    BufferMode.BALANCED -> R.string.buffer_balanced
    BufferMode.SMOOTH -> R.string.buffer_smooth
}
