package app.tuned.core

/** Reads the answer of `player_api.php` (see [XtreamUrls.account]) without a JSON library. */
object XtreamAccount {
    private val auth = Regex(""""auth"\s*:\s*"?(\d+)""")
    private val timezone = Regex(""""timezone"\s*:\s*"([^"]+)"""")

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
}
