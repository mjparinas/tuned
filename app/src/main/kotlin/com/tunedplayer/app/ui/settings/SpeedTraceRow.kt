package com.tunedplayer.app.ui.settings

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunedplayer.app.R
import com.tunedplayer.app.diagnostics.SpeedTrace
import com.tunedplayer.app.diagnostics.launchSafely
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Settings › Updates and advanced › Record a speed trace ([SpeedTrace]; Melvin, 2026-11-28). One tap restarts Tuned and
 * times its start and the next 20 seconds; the row says it is restarting, counts down while it records, and then says
 * how long Home took to draw, with the full timings in Logs.
 */
@Composable
internal fun SpeedTraceRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by SpeedTrace.status.collectAsStateWithLifecycle()
    var restarting by remember { mutableStateOf(false) }
    val left by produceState(0L, state) {
        val s = state
        while (s is SpeedTrace.State.Recording) {
            value = ((s.endsAt - SystemClock.elapsedRealtime()) / MS_PER_S).coerceAtLeast(0)
            delay(MS_PER_S)
        }
    }
    val summary =
        when {
            restarting -> {
                stringResource(R.string.settings_speed_trace_restarting)
            }

            state is SpeedTrace.State.Recording -> {
                stringResource(R.string.settings_speed_trace_recording, left.toInt())
            }

            state is SpeedTrace.State.Done -> {
                val home = (state as SpeedTrace.State.Done).homeMs
                if (home == null) {
                    stringResource(R.string.settings_speed_trace_done)
                } else {
                    stringResource(R.string.settings_speed_trace_done_home, String.format(Locale.getDefault(), "%.2f", home / 1_000.0))
                }
            }

            else -> {
                stringResource(R.string.settings_speed_trace_summary)
            }
        }
    val busy = restarting || state is SpeedTrace.State.Recording
    SettingRow(
        stringResource(R.string.settings_speed_trace),
        summary,
        onClick =
            if (busy) {
                null
            } else {
                {
                    restarting = true
                    scope.launchSafely("SpeedTrace", onError = { restarting = false }) {
                        // A moment for the row to say it is restarting before the app closes.
                        delay(RESTART_PAUSE_MS)
                        SpeedTrace.recordNextStart(context)
                    }
                }
            },
        trailing = if (busy) ({ RowSpinner() }) else null,
    )
}

private const val MS_PER_S = 1_000L
private const val RESTART_PAUSE_MS = 600L
