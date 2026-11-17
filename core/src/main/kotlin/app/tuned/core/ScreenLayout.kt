package app.tuned.core

/**
 * How Tuned lays itself out for the space it has, by width in dp, following Material's window size classes:
 * phones (compact) get the bottom bar, tablets and unfolded foldables get a navigation rail, and the widest
 * screens (expanded) also open a film, show or channel guide in a pane beside the list instead of over it.
 */
object ScreenLayout {
    const val MEDIUM_DP = 600
    const val EXPANDED_DP = 840

    /** The narrowest list the side-by-side panes leave; below this the detail opens full screen as on a phone. */
    const val MIN_LIST_DP = 360

    /** The guide beside the player, and the detail pane's share of a wide screen. */
    const val GUIDE_PANE_DP = 360
    const val DETAIL_FRACTION = 0.45f

    enum class Width { COMPACT, MEDIUM, EXPANDED }

    fun width(widthDp: Int): Width = when {
        widthDp >= EXPANDED_DP -> Width.EXPANDED
        widthDp >= MEDIUM_DP -> Width.MEDIUM
        else -> Width.COMPACT
    }

    /** The rail replaces the bottom bar once the screen is no longer phone-sized. */
    fun rail(widthDp: Int) = width(widthDp) != Width.COMPACT

    /** Opening something shows it beside the list only when both still have room. */
    fun panes(widthDp: Int) = width(widthDp) == Width.EXPANDED && widthDp * (1 - DETAIL_FRACTION) >= MIN_LIST_DP

    /** Posters grow a little on big screens so a tablet shows more of each, not just more of them. */
    fun posterMinDp(widthDp: Int) = if (width(widthDp) == Width.COMPACT) 112 else 140

    /**
     * The player's channel guide sits beside the video, which shrinks to make room, on a tablet in landscape (wide,
     * and at least 600 dp the short way, so a phone on its side doesn't count); otherwise it slides over the video.
     */
    fun guideBesideVideo(
        widthDp: Int,
        heightDp: Int,
    ) = widthDp > heightDp && width(widthDp) == Width.EXPANDED && heightDp >= MEDIUM_DP
}
