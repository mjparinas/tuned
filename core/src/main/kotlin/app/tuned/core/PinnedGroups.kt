package app.tuned.core

/**
 * Groups pinned to the front of a tab's chip row, in the order they were pinned (Melvin, 2026-11-19: "your own quick
 * chips", from the Horizon Maps review). With hundreds of groups, the few someone watches every day sit first instead
 * of somewhere along the row. At most [MAX] per tab: pinning one more lets go of the oldest, so the row never fills up
 * with pins. Kept as [HiddenGroups] keys, one per line, so Live TV, Movies and Shows can each pin a group of the same name.
 */
object PinnedGroups {
    const val MAX = 5

    fun decode(stored: String): List<String> = stored.split('\n').filter { it.isNotEmpty() }

    fun encode(keys: List<String>): String = keys.joinToString("\n")

    fun isPinned(
        stored: String,
        kind: Int,
        group: String,
    ): Boolean = HiddenGroups.key(kind, group) in decode(stored)

    /** Pins [group] to the end of [kind]'s pins, or unpins it if it already is. Returns the new stored value. */
    fun toggle(
        stored: String,
        kind: Int,
        group: String,
    ): String {
        val key = HiddenGroups.key(kind, group)
        val keys = decode(stored)
        if (key in keys) return encode(keys - key)
        val sameKind = keys.filter { it.startsWith(HiddenGroups.key(kind, "")) }
        val dropped = if (sameKind.size >= MAX) sameKind.take(sameKind.size - MAX + 1).toSet() else emptySet()
        return encode(keys.filterNot { it in dropped } + key)
    }

    /** [groups] with [kind]'s pinned ones first, in pin order, then the rest as they were. */
    fun <T> order(
        groups: List<T>,
        stored: String,
        kind: Int,
        name: (T) -> String,
    ): List<T> {
        val pins = decode(stored)
        if (pins.isEmpty()) return groups
        val rank = pins.withIndex().associate { (i, key) -> key to i }
        val (pinned, rest) = groups.partition { HiddenGroups.key(kind, name(it)) in rank }
        return pinned.sortedBy { rank.getValue(HiddenGroups.key(kind, name(it))) } + rest
    }
}
