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

/**
 * Seats per Tuned account (docs/decisions/accounts.md): Premium's [DeviceSeats.LIMIT] plus extra seats, at most
 * [MAX] in all. Extra seats match the Premium kind (Melvin, 2026-11-19: "make it so you can't mix lifetime account
 * and yearly seats"): lifetime Premium takes one-time seats, yearly Premium yearly ones.
 */
object AccountSeats {
    const val MAX = 10
    const val SEAT_ONCE = "seat_once"
    const val SEAT_YEARLY = "seat_yearly"

    /** The seat product that matches Premium of [premium] ("lifetime" or "yearly"); null without Premium. */
    fun seatProduct(premium: String?): String? = when (premium) {
        "lifetime" -> SEAT_ONCE
        "yearly" -> SEAT_YEARLY
        else -> null
    }

    /**
     * The account's answer as the device list's: "active" and "full" as they are, and anything else ("none" when
     * the server couldn't confirm Premium, say Play was down) as no answer, so it never locks anyone out.
     */
    fun answer(
        seat: String,
        devices: List<SeatDevice>,
    ): SeatAnswer = when (seat) {
        "active" -> SeatAnswer.Active(devices)
        "full" -> SeatAnswer.Full(devices)
        else -> SeatAnswer.Unreachable
    }

    /**
     * How many more seats Play can sell in one purchase. A one-time seat can be bought in any quantity up to the
     * cap; a yearly seat is a Play subscription, which an account holds once, so Play adds at most one.
     */
    fun playCanBuy(
        product: String,
        canBuy: Int,
        ownsYearlySeat: Boolean,
    ): Int = when {
        canBuy <= 0 -> 0
        product == SEAT_YEARLY -> if (ownsYearlySeat) 0 else 1
        else -> canBuy
    }
}
