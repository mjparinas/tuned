package app.tuned.core

/**
 * Moving through a long list or grid with a TV remote a page at a time (Melvin, 2026-11-19: "Optimize TV for remotes").
 * One press of an arrow moves one row, which is slow with thousands of channels, so the page and channel keys jump by
 * a screenful. Key codes are Android's `KeyEvent.KEYCODE_*` values ([RemoteKeys]), so the rule is tested on the JVM.
 */
object ListPaging {
    /** +1 for a page down, -1 for a page up, 0 for any other key. Channel up goes up the list, as on a cable box. */
    fun direction(keyCode: Int): Int = when (keyCode) {
        RemoteKeys.PAGE_DOWN, RemoteKeys.CHANNEL_DOWN -> 1
        RemoteKeys.PAGE_UP, RemoteKeys.CHANNEL_UP -> -1
        else -> 0
    }

    /**
     * The index to land on: [page] items on from [current] in [direction], kept within [first] (the list's first
     * item that can take focus, after any rows above it) and the last of [count] items. A page is at least one item.
     */
    fun target(
        current: Int,
        direction: Int,
        page: Int,
        count: Int,
        first: Int = 0,
    ): Int {
        if (count <= 0) return current
        val step = page.coerceAtLeast(1) * direction
        return (current + step).coerceIn(first.coerceIn(0, count - 1), count - 1)
    }
}
