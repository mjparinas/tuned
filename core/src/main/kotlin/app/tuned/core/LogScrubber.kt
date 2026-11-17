package app.tuned.core

/**
 * Takes logins out of text before it is logged (docs/decisions/security.md). Works without knowing the login: an
 * Xtream stream path (`/live/<user>/<pass>/…`), a login in the address (`http://user:pass@host`) and query values such
 * as `password=` all become `•••`. [XtreamUrls.redact] still runs on top with the login in use, for anything else.
 */
object LogScrubber {
    private const val MASK = "•••"

    private val streamPath =
        Regex("""(/(?:live|movie|series|timeshift)/)[^/\s?#]+/[^/\s?#]+/""", RegexOption.IGNORE_CASE)
    private val userInfo = Regex("""(\b[a-z][a-z0-9+.-]*://)[^/\s@]+@""", RegexOption.IGNORE_CASE)
    private val secretParam =
        Regex(
            """([?&;](?:username|user|password|pass|pwd|token|auth|api_key|apikey|key|access_token)=)[^&\s#"']*""",
            RegexOption.IGNORE_CASE,
        )
    private val bearer = Regex("""(\bBearer\s+)[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)

    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        var out = streamPath.replace(text) { it.groupValues[1] + "$MASK/$MASK/" }
        out = userInfo.replace(out) { it.groupValues[1] + "$MASK@" }
        out = secretParam.replace(out) { it.groupValues[1] + MASK }
        out = bearer.replace(out) { it.groupValues[1] + MASK }
        return out
    }
}
