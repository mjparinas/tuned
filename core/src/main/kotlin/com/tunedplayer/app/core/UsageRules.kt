package com.tunedplayer.app.core

import kotlin.random.Random

/**
 * Anonymous usage stats (Melvin, 2026-11-19: "Yes, like Liftsmith"; docs/decisions/admin.md). The server keeps only
 * what its allow-list names (`devices/lib/admin/analytics.mjs`), and the app sends only these names, so nothing
 * watched, typed or signed in with can leave the device by mistake.
 */
object UsageRules {
    /**
     * Where the stats start off until the person switches them on: the EU, the rest of the EEA, the UK and
     * Switzerland, with their territories that share the rules.
     */
    val OPT_IN_COUNTRIES: Set<String> =
        setOf(
            // EU
            "AT",
            "BE",
            "BG",
            "HR",
            "CY",
            "CZ",
            "DK",
            "EE",
            "FI",
            "FR",
            "DE",
            "GR",
            "HU",
            "IE",
            "IT",
            "LV",
            "LT",
            "LU",
            "MT",
            "NL",
            "PL",
            "PT",
            "RO",
            "SK",
            "SI",
            "ES",
            "SE",
            // EU outermost regions and Åland, which have country codes of their own
            "GF",
            "GP",
            "MQ",
            "RE",
            "YT",
            "MF",
            "AX",
            // EEA, the UK and its Crown dependencies and Gibraltar, Switzerland
            "IS",
            "LI",
            "NO",
            "GB",
            "UK",
            "GG",
            "JE",
            "IM",
            "GI",
            "CH",
        )

    /** Atlantic time zones in the countries above; every `Europe/` zone also counts. */
    private val OPT_IN_ZONES = setOf("Atlantic/Reykjavik", "Atlantic/Canary", "Atlantic/Madeira", "Atlantic/Azores", "Atlantic/Faroe")

    /**
     * Whether the stats start on: only when the device gives some sign of where it is and no sign points to a country
     * above. Any one sign (the SIM, the mobile network, the language's region or the time zone) inside them keeps them
     * off, and so does knowing nothing, so a mistake always leans to not sending.
     */
    fun onByDefault(
        countries: List<String?>,
        timeZone: String?,
    ): Boolean {
        val known = countries.mapNotNull { it?.trim()?.uppercase()?.takeIf(String::isNotEmpty) }
        val zone = timeZone?.trim().orEmpty()
        if (known.isEmpty() && zone.isEmpty()) return false
        if (known.any { it in OPT_IN_COUNTRIES }) return false
        return !(zone.startsWith("Europe/") || zone in OPT_IN_ZONES)
    }

    /** A new install id: 32 random hex characters, never Android's advertising id or anything about the device. */
    fun newInstallId(random: Random = Random.Default): String = buildString(ID_LENGTH) {
        repeat(ID_LENGTH) { append(HEX[random.nextInt(HEX.length)]) }
    }

    fun isInstallId(id: String?): Boolean = id != null && id.length == ID_LENGTH && id.all { it in HEX }

    /** The events the server's allow-list takes. Anything else is never queued. */
    val EVENTS: Set<String> =
        setOf(
            "app_open",
            "playlist_added",
            "playlist_refreshed",
            "first_play",
            "play",
            "play_failed",
            "screen",
            "feature",
            "premium_seen",
            "premium_bought",
            "premium_restored",
            "seat_full",
            "account_signed_in",
            "backup_saved",
            "backup_restored",
            "crash",
            "update_installed",
            "signed_out",
        )

    /** At most this many events wait on the device; past it the oldest go, so an offline device never fills up. */
    const val MAX_QUEUED = 500

    /** The server takes at most this many in one upload. */
    const val BATCH = 100

    /** Whether it is time to upload: the gap the admin set has passed, or a full batch is waiting. */
    fun dueToSend(
        queued: Int,
        lastSentMs: Long,
        nowMs: Long,
        batchMinutes: Int,
    ): Boolean = queued > 0 && (queued >= BATCH || nowMs - lastSentMs >= batchMinutes * MS_PER_MINUTE)

    private const val ID_LENGTH = 32
    private const val HEX = "0123456789abcdef"
    private const val MS_PER_MINUTE = 60_000L
}

/**
 * The switches the app reads from `GET /api/flags` (docs/decisions/admin.md), with the server's defaults
 * (`devices/lib/admin/flags.mjs`), so a device that has never reached the server behaves as before. Most are
 * off-switches for features that lean on someone else's service.
 */
data class RemoteFlags(
    val tmdb: Boolean = true,
    val subtitleSearch: Boolean = true,
    val trakt: Boolean = true,
    val tvSignIn: Boolean = true,
    val accountBackup: Boolean = true,
    val autoTune: Boolean = true,
    val channelScan: Boolean = true,
    val downloads: Boolean = true,
    val cast: Boolean = true,
    /** The website's provider check and Channels and guide page; the app shares its channel list only while it's on. */
    val webPlayer: Boolean = false,
    val usageBatchMinutes: Int = 60,
    val minVersionCode: Int = 0,
) {
    /** Whether this version is older than the oldest the admin lets run without updating. */
    fun tooOld(versionCode: Int): Boolean = minVersionCode > 0 && versionCode < minVersionCode

    companion object {
        /**
         * Reads the server's `flags` object (values already parsed to Boolean or a number). A missing, mistyped or
         * out-of-range value keeps its default, so a bad answer can never switch something off by accident.
         */
        fun from(values: Map<String, Any?>): RemoteFlags {
            val d = RemoteFlags()

            fun bool(
                key: String,
                default: Boolean,
            ) = values[key] as? Boolean ?: default

            fun int(
                key: String,
                default: Int,
                range: IntRange,
            ) = (values[key] as? Number)
                ?.toDouble()
                ?.takeIf { it % 1.0 == 0.0 && it >= range.first && it <= range.last }
                ?.toInt() ?: default
            return RemoteFlags(
                tmdb = bool("tmdb", d.tmdb),
                subtitleSearch = bool("subtitle_search", d.subtitleSearch),
                trakt = bool("trakt", d.trakt),
                tvSignIn = bool("tv_sign_in", d.tvSignIn),
                accountBackup = bool("account_backup", d.accountBackup),
                autoTune = bool("auto_tune", d.autoTune),
                channelScan = bool("channel_scan", d.channelScan),
                downloads = bool("downloads", d.downloads),
                cast = bool("cast", d.cast),
                webPlayer = bool("web_player", d.webPlayer),
                usageBatchMinutes = int("usage_batch_minutes", d.usageBatchMinutes, BATCH_MINUTES),
                minVersionCode = int("min_version_code", d.minVersionCode, VERSION_CODES),
            )
        }

        private val BATCH_MINUTES = 15..1440
        private val VERSION_CODES = 0..2_000_000_000
    }
}
