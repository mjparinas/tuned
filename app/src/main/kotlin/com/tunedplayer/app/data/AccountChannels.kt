package com.tunedplayer.app.data

import com.tunedplayer.app.core.ChannelShare
import com.tunedplayer.app.core.Classifier
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.PlaylistEntry
import com.tunedplayer.app.core.Programme
import com.tunedplayer.app.core.Regions
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Fills Live TV from the copy of this playlist kept in the Tuned account ([SharedChannels]) when the first download
 * from the provider fails or stalls (Melvin, 2026-11-20: "if we can't get the playlist from the provider but the user
 * has it on their account, just get it from there and we can refresh the real one later"). The copy holds the live
 * channels and about 12 hours of guide; films and shows come with the next refresh from the provider, which runs
 * later as usual. Links in the copy have the login hidden, and this device fills in its own.
 */
class AccountChannels(
    private val account: TunedAccount,
    private val sync: SyncRepository,
    private val login: () -> Credentials?,
) {
    /** True when the channels came from the account's copy. Never throws, except to let a cancellation through. */
    suspend fun fill(): Boolean {
        if (!account.signedIn.value) return false
        val c = login() ?: return false
        // A portal's links need a session the copy can't carry, so portals aren't kept there (SharedChannels).
        if (c.kind == Credentials.Kind.Stalker) return false
        val id = ChannelShare.playlistId(c.server, c.username, c.playlistUrl)
        val text = attempt(TAG, "Couldn't fetch the account's channel list") { account.channelList(id) }.getOrNull() ?: return false
        val copy = attempt(TAG, "Couldn't read the account's channel list") { read(text, c.username, c.password) }.getOrNull()
        if (copy == null || copy.channels.isEmpty()) return false
        val saved =
            attempt(TAG, "Couldn't save the account's channel list") { sync.fillFromCopy(copy.channels, copy.guide) }.getOrDefault(0)
        if (saved > 0) AppLog.i(TAG) { "The provider didn't answer; filled $saved channels from the Tuned account" }
        return saved > 0
    }

    /** The account's copy, read back into channels and programmes. */
    data class Copy(
        val channels: List<PlaylistEntry>,
        val guide: List<Programme>,
    )

    companion object {
        private const val TAG = "AccountChannels"
        private const val MS = 1000L
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads [text] (core ChannelShare's JSON), filling each link with this device's own login. */
        fun read(
            text: String,
            username: String,
            password: String,
        ): Copy {
            val root = json.parseToJsonElement(text).jsonObject
            val channels =
                root["channels"]?.jsonArray.orEmpty().mapIndexedNotNull { order, element ->
                    val row = element as? JsonArray ?: return@mapIndexedNotNull null
                    val name = row.text(0) ?: return@mapIndexedNotNull null
                    val link = row.text(5)?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                    val group = row.text(1).orEmpty()
                    PlaylistEntry(
                        name = name,
                        url = ChannelShare.showLogin(link, username, password),
                        kind = ContentKind.LIVE,
                        group = group,
                        tvgId = row.text(3)?.takeIf { it.isNotBlank() },
                        logo = row.text(2)?.takeIf { it.isNotBlank() },
                        region = Regions.detect(group, null, name),
                        adult = Classifier.isAdult(group, name),
                        episode = null,
                        order = order,
                    )
                }
            val guide = ArrayList<Programme>()
            (root["guide"] as? JsonObject)?.forEach { (channelId, shows) ->
                (shows as? JsonArray).orEmpty().mapNotNullTo(guide) { show -> programme(channelId, show as? JsonArray) }
            }
            return Copy(channels, guide)
        }

        /** One `[start, stop, title]` row, in epoch seconds; null when it isn't one. */
        private fun programme(
            channelId: String,
            row: JsonArray?,
        ): Programme? {
            val start = (row?.getOrNull(0) as? JsonPrimitive)?.longOrNull
            val stop = (row?.getOrNull(1) as? JsonPrimitive)?.longOrNull
            val title = row?.text(2)
            return if (start == null || stop == null || title == null) null else Programme(channelId, start * MS, stop * MS, title, null)
        }

        private fun JsonArray.text(index: Int): String? = (getOrNull(index) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
