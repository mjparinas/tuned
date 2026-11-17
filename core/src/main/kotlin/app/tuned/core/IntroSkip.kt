package app.tuned.core

import kotlin.math.abs

/**
 * Skip intro (Melvin, 2026-11-17). IPTV servers don't mark where an intro is, so Tuned offers a "Skip intro" button
 * near the start of each episode that jumps ahead by the length set in Settings, and learns each show's real intro: a
 * skip forward by hand near the start of an episode (from [LEARN_BEFORE_MS] or earlier, by [MIN_JUMP_MS] or more, to
 * [LEARN_UNTIL_MS] at most) is remembered as where that show's intro starts and ends.
 */
object IntroSkip {
    const val LEARN_BEFORE_MS = 4 * 60_000L
    const val LEARN_UNTIL_MS = 6 * 60_000L
    const val MIN_JUMP_MS = 10_000L

    /** The button shows from a little before a learned intro starts, since cold opens vary by a few seconds. */
    const val EARLY_MS = 15_000L

    /** And hides just before the intro ends, when skipping would barely move. */
    const val LATE_MS = 3_000L

    data class Intro(
        val startMs: Long,
        val endMs: Long,
    )

    /** The intro to offer: the show's learned one, otherwise the first [defaultLengthMs] (0 turns the button off). */
    fun intro(
        learned: Intro?,
        defaultLengthMs: Long,
    ): Intro? = when {
        defaultLengthMs <= 0 -> null
        learned != null -> learned
        else -> Intro(0, defaultLengthMs)
    }

    fun showButton(
        positionMs: Long,
        intro: Intro?,
    ): Boolean = intro != null && positionMs >= intro.startMs - EARLY_MS && positionMs < intro.endMs - LATE_MS

    /** Taps of the skip-forward button a few seconds apart count as one skip. */
    const val CHAIN_MS = 5_000L

    /**
     * A seek by hand from [fromMs] to [toMs], read as the intro it skipped, or null when it doesn't look like one. A seek
     * that starts where [previous] (the last one learned in this episode) ended extends it, so three taps of +30 s read
     * as one 90 s intro.
     */
    fun learn(
        fromMs: Long,
        toMs: Long,
        previous: Intro? = null,
    ): Intro? {
        if (toMs > LEARN_UNTIL_MS || toMs - fromMs < MIN_JUMP_MS) return null
        if (previous != null && abs(fromMs - previous.endMs) <= CHAIN_MS) return Intro(previous.startMs, toMs)
        if (fromMs > LEARN_BEFORE_MS) return null
        return Intro(fromMs.coerceAtLeast(0), toMs)
    }

    fun encode(intro: Intro): String = "${intro.startMs}:${intro.endMs}"

    fun decode(text: String?): Intro? {
        val (start, end) = text?.split(':')?.takeIf { it.size == 2 } ?: return null
        val s = start.toLongOrNull() ?: return null
        val e = end.toLongOrNull() ?: return null
        return Intro(s, e).takeIf { e > s }
    }
}
