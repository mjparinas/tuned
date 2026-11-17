package app.tuned.core

/**
 * GitHub release tags are the version, `v1.2.0` (Melvin, 2026-11-17: no build number). The version code release.yml
 * builds with comes from the version (see [code]), so the app compares codes. Older releases were tagged
 * `v0.1.0-build.42`, with the run number as the code; those still read, and every plain tag counts as newer.
 */
object ReleaseTag {
    private val plain = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")
    private val legacy = Regex("""^v?(.+)-build\.(\d+)$""")
    private const val PART = 1_000

    fun of(versionName: String): String = "v$versionName"

    /** The version code for a version name: 1.2.3 is 1002003. scripts/next-version.mjs works it out the same way. */
    fun code(versionName: String): Int? {
        val (major, minor, patch) = plain.find(versionName.trim())?.destructured ?: return null
        val parts = listOf(major, minor, patch).map { it.toIntOrNull() ?: return null }
        if (parts[1] >= PART || parts[2] >= PART) return null
        return (parts[0] * PART + parts[1]) * PART + parts[2]
    }

    /** The version code in a tag, or null for a tag that isn't one of ours. */
    fun versionCode(tag: String): Int? {
        val t = tag.trim()
        return code(t) ?: legacy
            .find(t)
            ?.groupValues
            ?.get(2)
            ?.toIntOrNull()
    }

    fun versionName(tag: String): String? {
        val t = tag.trim()
        if (plain.matches(t)) return t.removePrefix("v")
        return legacy.find(t)?.groupValues?.get(1)
    }

    fun isNewer(
        tag: String,
        installedCode: Int,
    ): Boolean = (versionCode(tag) ?: 0) > installedCode

    /** How often Tuned looks for a new release on its own: each time it comes to the front, at most once an hour. */
    const val CHECK_EVERY_MS = 3_600_000L

    /**
     * Whether to ask GitHub now. A check the person asked for always goes; otherwise once [CHECK_EVERY_MS] has passed
     * since the last one (or the clock went backwards).
     */
    fun checkDue(
        lastCheckMs: Long,
        nowMs: Long,
        force: Boolean,
    ): Boolean = force || lastCheckMs == 0L || nowMs - lastCheckMs >= CHECK_EVERY_MS || nowMs < lastCheckMs
}
