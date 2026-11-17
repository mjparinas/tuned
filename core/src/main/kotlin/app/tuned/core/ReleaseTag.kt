package app.tuned.core

/**
 * GitHub release tags look like `v0.1.0-build.42`: the version name, then the version code that release.yml set
 * (its run number). The app compares the code, so a rebuilt 0.1.0 still counts as newer.
 */
object ReleaseTag {
    private val pattern = Regex("""^v?(.+)-build\.(\d+)$""")

    fun of(
        versionName: String,
        versionCode: Int,
    ): String = "v$versionName-build.$versionCode"

    /** The version code in a tag, or null for a tag that isn't one of ours. */
    fun versionCode(tag: String): Int? = pattern
        .find(tag.trim())
        ?.groupValues
        ?.get(2)
        ?.toIntOrNull()

    fun versionName(tag: String): String? = pattern.find(tag.trim())?.groupValues?.get(1)

    fun isNewer(
        tag: String,
        installedCode: Int,
    ): Boolean = (versionCode(tag) ?: 0) > installedCode
}
