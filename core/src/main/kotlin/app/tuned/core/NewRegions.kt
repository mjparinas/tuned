package app.tuned.core

/**
 * What a country found for the first time does (Melvin, 2026-11-18: with only US and Canada chosen, Swedish channels
 * still showed). Settings keeps the countries turned off, so a country a later refresh finds, or one a better rule
 * starts to read, would show by default. A new country follows what most of the known ones do: someone who hid most
 * countries sees only what they chose, and someone who hid one or two still sees new ones.
 */
object NewRegions {
    data class Settled(
        val hidden: Set<String>,
        val seen: Set<String>,
    )

    /**
     * [found] are the regions in the playlist now, [seen] those known before, [hidden] the ones turned off. Returns the
     * new hidden and seen sets, or null when nothing changes. The first time ([seen] empty) only remembers [found].
     */
    fun settle(
        found: Set<String>,
        seen: Set<String>,
        hidden: Set<String>,
    ): Settled? {
        if (seen.isEmpty()) return if (found.isEmpty()) null else Settled(hidden, found)
        val new = found - seen
        if (new.isEmpty()) return null
        val hiddenBefore = seen.count { it in hidden }
        val hideNew = hiddenBefore > seen.size - hiddenBefore
        return Settled(if (hideNew) hidden + new else hidden, seen + new)
    }
}
