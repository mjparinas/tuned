package com.tunedplayer.app.core

import kotlin.math.roundToInt

/**
 * The scroll bar on Live TV, Movies and Shows. These lists hold tens of thousands of rows, so the thumb is a fixed
 * size (a thumb sized to the share on screen would be a sliver) and its position is how far down the list the top
 * row is. Dragging it maps the finger back to a row, so a long list can be crossed in one move.
 */
object FastScroll {
    /** Where the thumb sits, from 0 (top) to 1 (bottom), with [first] the top row and [visible] the rows on screen. */
    fun fraction(
        first: Int,
        visible: Int,
        total: Int,
    ): Float {
        val last = total - visible
        if (last <= 0) return 0f
        return (first.toFloat() / last).coerceIn(0f, 1f)
    }

    /** The row to jump to when the thumb is dragged to [fraction] of the way down. */
    fun index(
        fraction: Float,
        visible: Int,
        total: Int,
    ): Int {
        val last = (total - visible).coerceAtLeast(0)
        return (fraction.coerceIn(0f, 1f) * last).roundToInt()
    }

    /** Only a list longer than a few screens needs one; a short list scrolls fine by hand. */
    fun needed(
        visible: Int,
        total: Int,
    ) = visible > 0 && total > visible * MIN_SCREENS

    /**
     * The bubble beside the thumb while it's dragged: the category of the row it points at, since playlists list
     * their streams category by category, with a live channel's number in front when numbers are shown.
     */
    fun label(
        group: String?,
        number: Int = 0,
    ): String? {
        val name = group?.trim().orEmpty()
        return when {
            number > 0 && name.isNotEmpty() -> "$number · $name"
            number > 0 -> number.toString()
            name.isNotEmpty() -> name
            else -> null
        }
    }

    private const val MIN_SCREENS = 3
}
