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

    /**
     * The longest intro Tuned believes. Real ones run 30 s to about 1:45; anything longer is someone fast-forwarding
     * through the opening scenes, and remembering it kept the button on screen minutes into an episode.
     */
    const val MAX_LENGTH_MS = 2 * 60_000L

    /** The button shows from a little before a learned intro starts, since cold opens vary by a few seconds. */
    const val EARLY_MS = 15_000L

    /** And hides just before the intro ends, when skipping would barely move. */
    const val LATE_MS = 3_000L

    data class Intro(
        val startMs: Long,
        val endMs: Long,
    )

    /**
     * The intro to offer: the show's learned one, otherwise the first [defaultLengthMs] (0 turns the button off). A
     * learned intro longer than [MAX_LENGTH_MS] (saved before that limit existed) is ignored.
     */
    fun intro(
        learned: Intro?,
        defaultLengthMs: Long,
    ): Intro? = when {
        defaultLengthMs <= 0 -> null
        learned != null && learned.endMs - learned.startMs <= MAX_LENGTH_MS -> learned
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
        val intro =
            when {
                previous != null && abs(fromMs - previous.endMs) <= CHAIN_MS -> Intro(previous.startMs, toMs)
                fromMs > LEARN_BEFORE_MS -> return null
                else -> Intro(fromMs.coerceAtLeast(0), toMs)
            }
        return intro.takeIf { it.endMs - it.startMs <= MAX_LENGTH_MS }
    }

    /**
     * One episode's skips by hand. The first one near the start ends the button for this episode (the person is past the
     * intro already), and what it teaches is for the show's next episodes. Skips in a row that run past what an intro
     * could be ([learn] says no to the next one) are fast-forwarding, so the episode teaches nothing.
     */
    class Lesson {
        /** The intro this episode teaches, or null. */
        var intro: Intro? = null
            private set

        /** Skips by hand here ran past any intro: forget them, and learn nothing more from this episode. */
        var spoiled = false
            private set

        /** True once the person skipped forward by hand near the start, so the button stays away. */
        val skipped get() = intro != null || spoiled

        /** A seek by hand; true when what this episode teaches changed. */
        fun seek(
            fromMs: Long,
            toMs: Long,
        ): Boolean {
            if (spoiled) return false
            val previous = intro
            val chained = previous != null && abs(fromMs - previous.endMs) <= CHAIN_MS && toMs > fromMs
            val next = learn(fromMs, toMs, previous)
            if (next != null) {
                intro = next
                return true
            }
            if (!chained) return false
            intro = null
            spoiled = true
            return true
        }
    }

    fun encode(intro: Intro): String = "${intro.startMs}:${intro.endMs}"

    fun decode(text: String?): Intro? {
        val (start, end) = text?.split(':')?.takeIf { it.size == 2 } ?: return null
        val s = start.toLongOrNull() ?: return null
        val e = end.toLongOrNull() ?: return null
        return Intro(s, e).takeIf { e > s }
    }
}
