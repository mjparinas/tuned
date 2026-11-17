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
}
