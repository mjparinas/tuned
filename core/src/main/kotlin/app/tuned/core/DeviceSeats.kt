package app.tuned.core

/** A device holding one of a Premium purchase's seats, as the device list returns it. */
data class SeatDevice(
    val id: String,
    val name: String,
    /** When the device last checked in, in epoch milliseconds. */
    val lastSeen: Long,
    /** True for the device asking. */
    val current: Boolean,
)

/** The device list's answer for this device. */
sealed interface SeatAnswer {
    /** This device holds a seat. */
    data class Active(
        val devices: List<SeatDevice>,
    ) : SeatAnswer

    /** Every seat is taken by other devices, so this one stays locked until one is deactivated. */
    data class Full(
        val devices: List<SeatDevice>,
    ) : SeatAnswer

    /** No answer: offline, the list is down, or it couldn't check the purchase with Play. */
    data object Unreachable : SeatAnswer
}

/**
 * Premium on at most [LIMIT] devices per purchase (Melvin, 2026-11-19: "like a phone, a tv, and maybe one more.
 * beyond that, it should stop them"). The one rule that keeps paying people from being wrongly locked out: only a
 * clear "full" answer locks a device. No answer keeps whatever this device last heard, and a device that has never
 * heard anything is let in and asked again later.
 */
object DeviceSeats {
    const val LIMIT = 3

    /** Devices that haven't checked in for this long give their seat back on their own (a lost or broken phone). */
    const val EXPIRY_DAYS = 60

    /**
     * Whether Premium is on for this device. [owned] is what Play says; [seat] is what this device last heard from
     * the list: true for a seat, false for full (or deactivated), null for never asked.
     */
    fun unlocked(
        owned: Boolean,
        seat: Boolean?,
    ): Boolean = owned && seat != false

    /** What this device remembers after [answer]: an unreachable list changes nothing. */
    fun remember(
        answer: SeatAnswer,
        seat: Boolean?,
    ): Boolean? = when (answer) {
        is SeatAnswer.Active -> true
        is SeatAnswer.Full -> false
        SeatAnswer.Unreachable -> seat
    }
}
