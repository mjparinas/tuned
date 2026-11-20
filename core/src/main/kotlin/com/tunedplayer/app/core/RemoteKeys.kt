package com.tunedplayer.app.core

/**
 * What a TV remote's buttons do in the player, beyond what Media3's controls already handle (play, pause, fast
 * forward, rewind, and showing the controls). Key codes are Android's `KeyEvent.KEYCODE_*` values, copied so the rule
 * can be tested on the JVM.
 */
object RemoteKeys {
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val MENU = 82
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val GUIDE = 172
    const val DIGIT_0 = 7
    const val DIGIT_9 = 16
    const val NUMPAD_0 = 144
    const val NUMPAD_9 = 153
    const val LAST_CHANNEL = 229

    enum class Action { NONE, NEXT_CHANNEL, PREVIOUS_CHANNEL, SEEK_FORWARD, SEEK_BACK, GUIDE, INFO, LAST_CHANNEL }

    /** The digit a number key types (the row above the letters or the number pad), or null. */
    fun digit(keyCode: Int): Int? = when (keyCode) {
        in DIGIT_0..DIGIT_9 -> keyCode - DIGIT_0
        in NUMPAD_0..NUMPAD_9 -> keyCode - NUMPAD_0
        else -> null
    }

    /** Typing a channel number: [typed] so far plus [digit], at most [MAX_DIGITS] long. */
    fun typeDigit(
        typed: String,
        digit: Int,
    ): String = (typed + digit).takeLast(MAX_DIGITS)

    const val MAX_DIGITS = 5

    /** How long after the last digit the player changes channel. */
    const val NUMBER_WAIT_MS = 1_500L

    /**
     * The action for [keyCode]. The arrows only act while the controls are hidden, so that once they show the arrows
     * move between buttons as usual: up and down change channel on live TV, left and right skip in films and episodes.
     * The channel, guide, menu and info buttons work either way.
     */
    fun action(
        keyCode: Int,
        live: Boolean,
        controlsShown: Boolean,
    ): Action = when (keyCode) {
        CHANNEL_UP, PAGE_UP -> if (live) Action.NEXT_CHANNEL else Action.NONE
        CHANNEL_DOWN, PAGE_DOWN -> if (live) Action.PREVIOUS_CHANNEL else Action.NONE
        GUIDE, MENU -> if (live) Action.GUIDE else Action.NONE
        INFO -> Action.INFO
        LAST_CHANNEL -> if (live) Action.LAST_CHANNEL else Action.NONE
        else -> if (controlsShown) Action.NONE else arrow(keyCode, live)
    }

    private fun arrow(
        keyCode: Int,
        live: Boolean,
    ): Action = when (keyCode) {
        DPAD_UP -> if (live) Action.NEXT_CHANNEL else Action.NONE
        DPAD_DOWN -> if (live) Action.PREVIOUS_CHANNEL else Action.NONE
        DPAD_RIGHT -> if (live) Action.NONE else Action.SEEK_FORWARD
        DPAD_LEFT -> if (live) Action.NONE else Action.SEEK_BACK
        else -> Action.NONE
    }
}
