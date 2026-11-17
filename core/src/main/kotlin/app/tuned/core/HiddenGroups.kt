package app.tuned.core

/**
 * Groups the person has hidden (Melvin, 2026-11-17: "hiding channel groups"). Each is kept as a key that names the
 * section too, since Live TV, Movies and Shows can have groups with the same name. The keys travel in the same list
 * as hidden country codes, so every query filters both with one parameter; the `g:` start keeps them apart.
 */
object HiddenGroups {
    private const val PREFIX = "g:"

    fun key(
        kind: Int,
        group: String,
    ): String = "$PREFIX$kind:$group"

    fun isGroupKey(key: String): Boolean = key.startsWith(PREFIX)
}
