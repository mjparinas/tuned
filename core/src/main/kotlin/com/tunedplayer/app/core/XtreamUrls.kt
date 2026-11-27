package com.tunedplayer.app.core

/** Where an Xtream Codes server keeps its playlist and guide, and how to keep login details out of logs. */
object XtreamUrls {
    fun normalizeServer(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "http://$trimmed"
    }

    /** `m3u_plus` carries the categories, logos and guide ids; `ts` streams start fastest on Media3. */
    fun playlist(
        server: String,
        username: String,
        password: String,
    ): String = "${normalizeServer(server)}/get.php?username=${enc(username)}&password=${enc(password)}&type=m3u_plus&output=ts"

    fun guide(
        server: String,
        username: String,
        password: String,
    ): String = "${normalizeServer(server)}/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    /** The account check: a small JSON answer whose `user_info.auth` is 1 for a good login. */
    fun account(
        server: String,
        username: String,
        password: String,
    ): String = "${normalizeServer(server)}/player_api.php?username=${enc(username)}&password=${enc(password)}"

    /** Every live channel with its catch-up details (`tv_archive`, `tv_archive_duration`). */
    fun liveStreams(
        server: String,
        username: String,
        password: String,
    ): String = account(server, username, password) + "&action=get_live_streams"

    /** Replaces the username and password wherever they appear (query or path), for error messages and logs. */
    fun redact(
        text: String,
        username: String,
        password: String,
    ): String {
        var out = text
        for (secret in listOf(password, enc(password), username, enc(username))) {
            if (secret.length >= 2) out = out.replace(secret, "•••")
        }
        return out
    }

    internal fun enc(value: String): String = Platform.formEncode(value)
}
