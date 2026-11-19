package app.tuned.core

/**
 * The user agent picklist (Melvin, 2026-11-19: "a picklist of the most common user agents that these iptv providers,
 * stbs use, and then have an option to use a custom one ... it should default to tuned"). Some servers only answer
 * the players they know, so Settings › About › User agent offers what those players send.
 *
 * The setting still stores the user agent itself (blank is Tuned's own), so nothing had to be migrated: a value that
 * matches a preset shows as that preset, and anything else someone typed before shows as Custom, unchanged.
 */
object UserAgents {
    /** One choice: [label] is the player's own name, which is the same in every language. */
    data class Preset(
        val id: String,
        val label: String,
        val value: String,
    )

    /** Tuned's own (`Tuned/<version>`), stored as blank so it follows the app's version. */
    val TUNED = Preset("tuned", "Tuned", "")

    val presets: List<Preset> =
        listOf(
            TUNED,
            Preset("vlc", "VLC", "VLC/3.0.21 LibVLC/3.0.21"),
            Preset("tivimate", "TiviMate", "TiviMate/5.1.6 (Android 12)"),
            Preset("smarters", "IPTV Smarters", "IPTVSmartersPro"),
            Preset("ott_navigator", "OTT Navigator", "OTT Navigator/1.7.2.2 (Linux;Android 12) ExoPlayerLib/2.19.1"),
            Preset(
                "kodi",
                "Kodi",
                "Kodi/21.2 (Linux; Android 12) Android/12 Sys_CPU/aarch64 App_Bitness/64 " +
                    "Version/21.2-(21.2.0)-Git:20250103",
            ),
            Preset(
                "mag",
                "MAG box (Stalker)",
                "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
                    "MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
            ),
            Preset("exoplayer", "ExoPlayer", "ExoPlayerLib/2.19.1"),
            Preset(
                "chrome",
                "Chrome",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/142.0.0.0 Safari/537.36",
            ),
        )

    /** The preset a stored user agent is, or null when it is someone's own (Custom). */
    fun presetFor(stored: String): Preset? {
        val ua = stored.trim()
        return presets.firstOrNull { it.value == ua }
    }
}
