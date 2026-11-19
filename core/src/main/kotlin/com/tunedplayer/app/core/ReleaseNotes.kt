package com.tunedplayer.app.core

/**
 * What's new in a release, read from its GitHub release notes. release.yml writes them with `--generate-notes`, so each
 * change is a line `* feat: keep playing in the background (#150) by @someone in https://…/pull/150` under "What's
 * Changed". Each line's Conventional Commits type sorts it: `feat` is new, `fix` is fixed, and `perf`, `refactor` and
 * plain titles are improved. Upkeep nobody would notice (dependency bumps, CI, docs, tests, formatting) is left out.
 */
object ReleaseNotes {
    enum class Kind { NEW, IMPROVED, FIXED }

    data class Change(
        val kind: Kind,
        val text: String,
    )

    private val item = Regex("""^\s*[*-]\s+(.+)$""")
    private val credit = Regex("""\s+by\s+@\S+(\s+in\s+\S+)?\s*$""")
    private val link = Regex("""\s+in\s+https?://\S+\s*$""")
    private val number = Regex("""\s*\(#\d+\)\s*$""")
    private val type = Regex("""^(\w+)(\([^)]*\))?(!)?:\s*(.+)$""")
    private val upkeep = setOf("build", "chore", "ci", "docs", "style", "test", "tests", "deps")

    /** The changes in [notes], new first, then improved, then fixed, each in the order the notes list them. */
    fun parse(notes: String): List<Change> = notes
        .lineSequence()
        .mapNotNull { line ->
            item
                .find(line)
                ?.groupValues
                ?.get(1)
                ?.let(::change)
        }.distinct()
        .sortedBy { it.kind.ordinal }
        .toList()

    /** How many of each kind there are, for the sheet's header. */
    fun counts(changes: List<Change>): Map<Kind, Int> = Kind.entries.associateWith { k -> changes.count { it.kind == k } }

    private fun change(raw: String): Change? {
        var text =
            raw
                .trim()
                .replace(credit, "")
                .replace(link, "")
                .replace(number, "")
                .trim()
        // "@someone made their first contribution" lines are thanks, not changes.
        if (text.isEmpty() || text.startsWith("@")) return null
        // Dependabot's titles ("Bump the gradle group …", "build(deps): bump …") are upkeep.
        if (text.startsWith("Bump ", ignoreCase = true)) return null
        val kind: Kind
        val m = type.find(text)
        if (m != null) {
            val t = m.groupValues[1].lowercase()
            val scope = m.groupValues[2]
            val rest = m.groupValues[4]
            if (t in upkeep || scope.contains("deps")) return null
            kind =
                when (t) {
                    "feat" -> Kind.NEW
                    "fix" -> Kind.FIXED
                    else -> Kind.IMPROVED
                }
            text = rest.trim()
        } else {
            if (text.startsWith("Promote ", ignoreCase = true) || text.startsWith("Merge ", ignoreCase = true)) return null
            kind = Kind.IMPROVED
        }
        if (text.isEmpty()) return null
        return Change(kind, text.replaceFirstChar { it.uppercaseChar() })
    }
}
