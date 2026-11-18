package app.tuned.core

/**
 * What a country found for the first time does (Melvin, 2026-11-18: with only US and Canada chosen, Swedish channels
 * still showed). Settings keeps the countries turned off, so a country a later refresh finds, or one a better rule
 * starts to read, would show by default. A new country follows what most of the known ones do: someone who hid most
 * countries sees only what they chose, and someone who hid one or two still sees new ones.
 *
 * Streams with no country ([Regions.NONE]) are the exception: they start hidden only when every known country is
 * hidden, because someone who kept only US and Canada still wants the untagged sports and event channels, while
 * someone who turned everything off wants nothing to show.
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
        val countries = seen - Regions.NONE
        val hiddenBefore = countries.count { it in hidden }
        val hideNew = hiddenBefore > countries.size - hiddenBefore
        val hideNone = countries.isNotEmpty() && hiddenBefore == countries.size
        val newHidden = new.filter { if (it == Regions.NONE) hideNone else hideNew }
        return Settled(hidden + newHidden, seen + new)
    }
}
