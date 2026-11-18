package app.tuned.core

/**
 * Catch-up (Xtream `tv_archive`): a channel that keeps recordings for a few days can replay any programme that has
 * started within them, from the beginning.
 */
object CatchUp {
    /** No provider keeps more than a week worth having in the guide; past programmes beyond it aren't stored. */
    const val MAX_DAYS = 7
    private const val DAY = 24 * 3_600_000L
    private const val MINUTE = 60_000L

    private val trailingId = Regex("""/(\d+)(\.[A-Za-z0-9]+)?$""")

    /** The Xtream stream id at the end of a live link (`…/live/user/pass/123.ts` or `…/user/pass/123`). */
    fun streamId(url: String): String? = trailingId.find(StreamLink.address(url).substringBefore('?'))?.groupValues?.get(1)

    /**
     * What the catch-up table keys a channel by: its Xtream stream id, or for a plain M3U link without one, the link
     * itself (without headers).
     */
    fun key(url: String): String = streamId(url) ?: StreamLink.address(url)

    /** Days of recordings from a `get_live_streams` entry; servers send the values as numbers or strings. */
    fun days(
        tvArchive: String?,
        duration: String?,
    ): Int = if (tvArchive?.trim() == "1") (duration?.trim()?.toIntOrNull() ?: 0).coerceIn(0, MAX_DAYS) else 0

    /** Whether a programme starting at [start] can be replayed now, on a channel that keeps [days] of recordings. */
    fun available(
        start: Long,
        now: Long,
        days: Int,
    ): Boolean = days > 0 && start < now && start >= now - days * DAY

    /** The oldest start time still worth keeping in the guide for a channel that keeps [days] of recordings. */
    fun keepFrom(
        now: Long,
        days: Int,
    ): Long = now - days.coerceIn(0, MAX_DAYS) * DAY

    /** Whole minutes covering a programme, as the timeshift link wants them. */
    fun minutes(
        start: Long,
        stop: Long,
    ): Int = ((stop - start + MINUTE - 1) / MINUTE).toInt().coerceAtLeast(1)
}

/**
 * A plain M3U channel's catch-up, from its `catchup`, `catchup-days` and `catchup-source` attributes, or the
 * playlist header's when the channel gives none (the attributes Kodi's IPTV Simple and TiviMate read). [mode] is
 * one of [M3uCatchUp.MODES]; [source] is the link template, which `default` and `append` need.
 */
data class M3uCatchUp(
    val mode: String,
    val days: Int,
    val source: String?,
) {
    /** Kept in the catch-up table's one text column: the mode, a line break, then the template. */
    fun encode(): String = mode + "\n" + source.orEmpty()

    companion object {
        const val DEFAULT = "default"
        const val APPEND = "append"
        const val SHIFT = "shift"
        const val FLUSSONIC = "flussonic"
        const val XC = "xc"
        val MODES = setOf(DEFAULT, APPEND, SHIFT, FLUSSONIC, XC)

        /** Days when a playlist names catch-up but not how long it keeps recordings; Kodi's default too. */
        const val UNKNOWN_DAYS = 5

        private val ALIASES =
            mapOf(
                "timeshift" to SHIFT,
                "fs" to FLUSSONIC,
                "flussonic-hls" to FLUSSONIC,
                "flussonic-ts" to FLUSSONIC,
                "flussonic_hls" to FLUSSONIC,
                "flussonic_ts" to FLUSSONIC,
                "xtream" to XC,
            )

        fun decode(text: String): M3uCatchUp? {
            val mode = text.substringBefore('\n')
            if (mode !in MODES) return null
            return M3uCatchUp(mode, 0, text.substringAfter('\n', "").ifBlank { null })
        }

        /** The channel's catch-up from its own [attributes], falling back to the playlist [header]'s; null if none. */
        fun from(
            attributes: Map<String, String>,
            header: Map<String, String>,
        ): M3uCatchUp? {
            fun read(vararg keys: String) = keys.firstNotNullOfOrNull { k -> attributes[k]?.takeIf { it.isNotBlank() } }
                ?: keys.firstNotNullOfOrNull { k -> header[k]?.takeIf { it.isNotBlank() } }
            val source = read("catchup-source")
            val daysText = read("catchup-days", "timeshift", "tvg-rec")
            val named = read("catchup", "catchup-type")?.trim()?.lowercase()
            val mode =
                when {
                    named != null -> ALIASES[named] ?: named.takeIf { it in MODES }

                    source != null -> DEFAULT

                    // A channel's own `timeshift="3"` alone: the server takes ?utc= on the live link.
                    !attributes["timeshift"].isNullOrBlank() -> SHIFT

                    else -> null
                } ?: return null
            if ((mode == DEFAULT || mode == APPEND) && source == null) return null
            val days = daysText?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: UNKNOWN_DAYS
            return M3uCatchUp(mode, days.coerceAtMost(CatchUp.MAX_DAYS), source)
        }
    }
}
