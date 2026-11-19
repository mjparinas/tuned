package app.tuned.core

/**
 * When "Lighter mode: Automatic" turns itself on: on a device Android calls low on memory, or after [FREEZES] freezes
 * within [WINDOW_MS]. A single freeze can be bad luck (a slow server, a big guide); two in a week is the device.
 */
object LighterRule {
    const val FREEZES = 2
    const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    fun auto(
        lowRam: Boolean,
        freezeTimesMs: List<Long>,
        nowMs: Long,
    ): Boolean = lowRam || freezeTimesMs.count { it in (nowMs - WINDOW_MS)..nowMs } >= FREEZES
}
