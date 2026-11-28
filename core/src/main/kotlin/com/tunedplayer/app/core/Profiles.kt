package com.tunedplayer.app.core

/**
 * Kids profiles see only kids and family categories (docs/decisions/profiles.md). A stream counts when its group
 * names one of [GROUP_WORDS], or, for a live channel, when its own name has one of [CHANNEL_WORDS] (a "Cartoon
 * Network" in "US | Entertainment"). Channel names leave out "family" and "animation", which match grown-up channels.
 *
 * The database applies the same rule when it saves a stream (`ItemTags` in `:data`), on the lower-case group and the normalized
 * name ([Search.normalize]). Keep the two in step; `ItemTagsTest` checks they agree.
 */
object KidsContent {
    /** Added to the hidden keys while a kids profile is in use. */
    const val HIDDEN_KEY = "kids"

    val GROUP_WORDS = listOf("kid", "child", "cartoon", "family", "junior", "disney", "nick", "baby", "animation", "boomerang")
    val CHANNEL_WORDS = listOf("kid", "child", "cartoon", "junior", "disney", "nick", "baby", "boomerang")

    fun isKids(
        group: String,
        name: String,
        live: Boolean,
    ): Boolean {
        val g = group.lowercase()
        if (GROUP_WORDS.any { it in g }) return true
        if (!live) return false
        val n = Search.normalize(name)
        return CHANNEL_WORDS.any { it in n }
    }
}

/**
 * Whether the player may open a stream, whatever asked for it (docs/decisions/profiles.md). The lists already hide
 * locked adult categories and, on a kids profile, everything but kids and family, but the player is also opened by the
 * quick-settings tile, the favourites widget, Android TV's home screen cards and other apps' play intents, which skip
 * the lists. Without this check the tile reopened an adult channel after the parental unlock had ended.
 */
object PlayGate {
    fun allowed(
        adult: Boolean,
        group: String,
        name: String,
        live: Boolean,
        showAdult: Boolean,
        kidsProfile: Boolean,
    ): Boolean = (!adult || (showAdult && !kidsProfile)) && (!kidsProfile || KidsContent.isKids(group, name, live))
}

/** Which PIN, if any, stands between the profile in use and the one picked. */
enum class ProfileLock { NONE, PROFILE, PARENTAL }

object ProfileRules {
    /**
     * At most six profiles (Melvin, 2026-11-18: "there should be [a limit] otherwise the ui will be cluttered", then "change
     * that to 6 so it's even"): six fill three even rows of two on a phone. A device that already has more keeps them.
     */
    const val MAX_PROFILES = 6

    fun canAdd(count: Int): Boolean = count < MAX_PROFILES

    /**
     * A profile with its own PIN always asks for it. Leaving a kids profile for a grown-up one without a PIN asks for
     * the parental PIN, so a child can't walk out of their profile; moving into a kids profile never asks.
     */
    fun lockFor(
        fromKids: Boolean,
        toKids: Boolean,
        toHasPin: Boolean,
        parentalPinSet: Boolean,
    ): ProfileLock = when {
        toHasPin -> ProfileLock.PROFILE
        fromKids && !toKids && parentalPinSet -> ProfileLock.PARENTAL
        else -> ProfileLock.NONE
    }
}

/**
 * Five wrong PINs in a row wait 30 seconds before the next try, so guessing all 10,000 four-digit PINs takes days.
 * One throttle covers every PIN on the device, so switching between profile and parental PINs doesn't reset it.
 */
class PinThrottle(
    private val clock: () -> Long,
    private val maxWrong: Int = 5,
    private val waitMs: Long = 30_000,
) {
    enum class Result { OK, WRONG, TOO_MANY }

    private var wrong = 0
    private var lockedUntil = 0L

    /** True while waiting out a lock: don't even check the PIN. */
    fun waiting(): Boolean = clock() < lockedUntil

    /** Counts one answer; call only when [waiting] is false. */
    fun record(ok: Boolean): Result {
        if (ok) {
            wrong = 0
            return Result.OK
        }
        if (++wrong >= maxWrong) {
            wrong = 0
            lockedUntil = clock() + waitMs
            return Result.TOO_MANY
        }
        return Result.WRONG
    }
}
