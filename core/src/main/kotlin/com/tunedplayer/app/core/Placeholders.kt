package com.tunedplayer.app.core

/**
 * Placeholder channels: event and pay-per-view slots a provider keeps in the playlist between events, named like
 * "- NO EVENT STREAMING - | 8K EXCLUSIVE | SE: PLAY+ PPV 43". They can run to hundreds and never play anything, so
 * they are left out of Live TV, search and auto-tune by default (Settings › Channel health). It goes by the name
 * alone, so once the provider renames a slot for a real event, it shows again with the next playlist refresh.
 *
 * The database applies the same rule in SQL (`ItemDao`), on the normalized name ([Search.normalize]):
 * `(' ' || searchName) GLOB '* no event*'` or `'* no live event*'`. Keep the two in step.
 */
object Placeholders {
    /** Added to the hidden keys while the setting is on. */
    const val HIDDEN_KEY = "placeholder"

    fun isPlaceholder(name: String): Boolean {
        val text = " " + Search.normalize(name)
        return " no event" in text || " no live event" in text
    }
}
