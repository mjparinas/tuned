package app.tuned.core

/** Reads the answer of `player_api.php` (see [XtreamUrls.account]) without a JSON library. */
object XtreamAccount {
    private val auth = Regex(""""auth"\s*:\s*"?(\d+)""")
    private val timezone = Regex(""""timezone"\s*:\s*"([^"]+)"""")
    private val active = Regex(""""active_cons"\s*:\s*"?(\d+)""")
    private val max = Regex(""""max_connections"\s*:\s*"?(\d+)""")

    /** True for a good login, false when the server turned it down, null when the answer says neither. */
    fun accepted(body: String): Boolean? {
        val value = auth.find(body)?.groupValues?.get(1) ?: return null
        return value != "0"
    }

    /** The server's time zone (`server_info.timezone`, such as `Europe/London`), which catch-up links are read in. */
    fun timezone(body: String): String? = timezone
        .find(body)
        ?.groupValues
        ?.get(1)
        ?.replace("\\/", "/")
        ?.takeIf { it.isNotBlank() }

    /**
     * Whether every connection the account allows is in use (`user_info.active_cons` against `max_connections`), as when
     * someone is watching on another device; null when the answer doesn't say.
     */
    fun connectionsFull(body: String): Boolean? {
        val inUse =
            active
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        val allowed =
            max
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        return allowed in 1..inUse
    }

    private val status = Regex(""""status"\s*:\s*"([^"]*)"""")
    private val expires = Regex(""""exp_date"\s*:\s*"?(\d+)""")
    private val trial = Regex(""""is_trial"\s*:\s*"?(\d)""")

    /** What Settings › Account shows (Melvin, 2026-11-17, from the app audit). */
    data class Info(
        /** "Active", "Expired", "Banned", "Disabled", as the server says it; null when it doesn't. */
        val status: String?,
        /** When the account runs out (epoch milliseconds), or null for no end date. */
        val expiresAt: Long?,
        val activeConnections: Int?,
        val maxConnections: Int?,
        val trial: Boolean,
    ) {
        /** Every connection the account allows is in use. */
        val full: Boolean get() = maxConnections != null && activeConnections != null && maxConnections in 1..activeConnections
    }

    /** Reads `user_info`; null when the answer isn't an accepted login. */
    fun info(body: String): Info? {
        if (accepted(body) != true) return null
        val seconds = first(expires, body)?.toLongOrNull()
        return Info(
            status = first(status, body)?.takeIf { it.isNotBlank() },
            expiresAt = seconds?.takeIf { it > 0 }?.times(MS_PER_SECOND),
            activeConnections = first(active, body)?.toIntOrNull(),
            maxConnections = first(max, body)?.toIntOrNull(),
            trial = first(trial, body) == "1",
        )
    }

    private fun first(
        regex: Regex,
        body: String,
    ): String? {
        val match = regex.find(body) ?: return null
        return match.groupValues[1]
    }

    /** Days left until [expiresAt], rounded down; 0 on the last day, negative once it has run out. */
    fun daysLeft(
        expiresAt: Long,
        now: Long,
    ): Long = Math.floorDiv(expiresAt - now, DAY_MS)

    private const val MS_PER_SECOND = 1_000L
    private const val DAY_MS = 86_400_000L
}
