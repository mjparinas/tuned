package app.tuned.core

/** Reads the answer of `player_api.php` (see [XtreamUrls.account]) without a JSON library. */
object XtreamAccount {
    private val auth = Regex(""""auth"\s*:\s*"?(\d+)""")

    /** True for a good login, false when the server turned it down, null when the answer says neither. */
    fun accepted(body: String): Boolean? {
        val value = auth.find(body)?.groupValues?.get(1) ?: return null
        return value != "0"
    }
}
