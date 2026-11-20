package com.tunedplayer.app.core

/**
 * Round-the-clock channels: a provider's "24/7" channels that loop one show or film series all day ("24/7 Friends",
 * "US| 24/7 CARTOONS", "24-7 Seinfeld", "247: The Simpsons", "Friends 24x7"). Melvin, 2026-11-17: "Add the ability to
 * hide 24/7 streams". Settings › Content › Hide 24/7 channels leaves them out of Live TV, search, auto-tune and channel
 * flipping, and leaves films and shows labelled 24/7 out of Movies and Shows (Melvin, 2026-11-18: "the hide 24/7
 * functionality should apply to movies and shows"). It is off by default, since many people watch them.
 *
 * The database applies the same rule in SQL (`ItemDao`, and `ROUND_THE_CLOCK_S` for the Shows tab's per-show table), on
 * the lower-cased category and name with a space at each end: `GLOB '*[^0-9]24[/ x-]7[^0-9]*'`, `'*[^0-9]247[^0-9]*'` or
 * `'*[^0-9]24 / 7[^0-9]*'`. Keep the two in step.
 */
object RoundTheClock {
    /** Added to the hidden keys while the setting is on. */
    const val HIDDEN_KEY = "247"

    private val tag = Regex("""(?<![0-9])24(?:[/ x-]| / )?7(?![0-9])""")

    fun is247(
        group: String,
        name: String,
    ): Boolean = tag.containsMatchIn(" ${group.lowercase()} ${name.lowercase()} ")
}
