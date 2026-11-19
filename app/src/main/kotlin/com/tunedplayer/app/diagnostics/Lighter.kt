package com.tunedplayer.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.edit
import com.tunedplayer.app.core.LighterRule
import com.tunedplayer.app.data.LighterMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lighter mode, for weak TV boxes (docs/decisions/speed.md): no picture fades, pictures held in half the memory, and
 * no pulsing or turning decorations. The answer is kept on the device as well as in [on], because the picture loader
 * is built before the settings have loaded and needs it at once; a change to it reaches the pictures on the next start
 * and everything else straight away.
 */
object Lighter {
    private const val TAG = "Lighter"
    private const val PREFS = "lighter"
    private const val ON = "on"
    private val state = MutableStateFlow(false)
    val on: StateFlow<Boolean> = state.asStateFlow()

    /** The answer from the last run, for the first frame and the picture loader. Reads one small preferences file. */
    fun load(context: Context): Boolean {
        state.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON, lowRam(context))
        return state.value
    }

    /** Works the answer out again from [mode], the device and its freezes. Lists a folder: off the main thread. */
    fun refresh(
        context: Context,
        mode: LighterMode,
    ) {
        val now =
            when (mode) {
                LighterMode.ON -> true
                LighterMode.OFF -> false
                LighterMode.AUTO -> LighterRule.auto(lowRam(context), CrashReporter.freezeTimes(context), System.currentTimeMillis())
            }
        if (now != state.value) AppLog.i(TAG) { "Lighter mode ${if (now) "on" else "off"} ($mode)" }
        state.value = now
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(ON, now) }
    }

    private fun lowRam(context: Context): Boolean = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
}
