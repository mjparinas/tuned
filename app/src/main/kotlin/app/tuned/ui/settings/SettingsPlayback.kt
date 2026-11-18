package app.tuned.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tuned.R
import app.tuned.data.AppSettings
import app.tuned.data.BufferMode

@Composable
internal fun PlaybackPage(
    settings: AppSettings,
    vm: SettingsViewModel,
) {
    ChoiceRow(
        stringResource(R.string.settings_audio_language),
        languageCodes,
        settings.audioLanguage,
        label = { languageName(it) },
    ) { l ->
        vm.update { it.copy(audioLanguage = l) }
    }
    SwitchRow(
        stringResource(R.string.settings_resume),
        settings.resumePlayback,
    ) { on -> vm.update { it.copy(resumePlayback = on) } }
    SwitchRow(stringResource(R.string.settings_autoplay), settings.autoPlayNext) { on -> vm.update { it.copy(autoPlayNext = on) } }
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
    ChoiceRow(
        stringResource(R.string.settings_buffer),
        BufferMode.entries,
        settings.buffer,
        label = { stringResource(bufferLabel(it)) },
    ) { b ->
        vm.update { it.copy(buffer = b) }
    }
    SectionHeader(stringResource(R.string.settings_header_screen))
    SwitchRow(
        stringResource(R.string.settings_landscape),
        settings.landscapePlayback,
    ) { on -> vm.update { it.copy(landscapePlayback = on) } }
    SwitchRow(
        stringResource(R.string.settings_pip),
        settings.pictureInPicture,
        stringResource(R.string.settings_pip_summary),
    ) { on ->
        vm.update { it.copy(pictureInPicture = on) }
    }
    SwitchRow(
        stringResource(R.string.settings_match_frame_rate),
        settings.matchFrameRate,
        stringResource(R.string.settings_match_frame_rate_summary),
    ) { on -> vm.update { it.copy(matchFrameRate = on) } }
    SwitchRow(
        stringResource(R.string.settings_gestures),
        settings.playerGestures,
        stringResource(R.string.settings_gestures_summary),
    ) { on -> vm.update { it.copy(playerGestures = on) } }
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
    SettingRow(stringResource(R.string.settings_clear_history), onClick = vm::clearHistory)
    SubtitleSettings(settings, vm::update)
}

private fun bufferLabel(mode: BufferMode): Int = when (mode) {
    BufferMode.FAST_START -> R.string.buffer_fast
    BufferMode.BALANCED -> R.string.buffer_balanced
    BufferMode.SMOOTH -> R.string.buffer_smooth
}
