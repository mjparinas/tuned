package com.tunedplayer.app.data

import android.content.Context
import androidx.core.content.edit
import com.tunedplayer.app.core.ChannelShare
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.diagnostics.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Keeps a copy of the playlist in use's live channels and the next 12 hours of its guide in the Tuned account, for
 * the website's Channels and guide page (docs/decisions/web.md › The app's copy). Melvin, 2026-11-20, chose "the app
 * shares its list" so the page works with providers that don't let web pages read them. Only while the admin's
 * `web_player` flag is on and the device is signed in to an account; only the list goes to Tuned, never a stream.
 *
 * Runs with the backup ([com.tunedplayer.app.work.CloudSyncWorker]): sent when the channel list changed, and every
 * [ChannelShare.REFRESH_MS] anyway so the website's guide stays ahead. The guide is read a few hundred channels at a
 * time and written straight into the gzipped copy, so a big playlist is never held whole. MAC portals aren't shared:
 * their links only work with a session a web page or VLC can't open.
 */
class SharedChannels(
    context: Context,
    private val db: TunedDatabase,
    private val login: () -> Credentials?,
    private val account: TunedAccount,
) {
    // The backup's own file: signing out of the account (CloudSyncRepository.forget) and AppReset clear it with the rest.
    private val prefs = context.getSharedPreferences("tuned.backup", Context.MODE_PRIVATE)
    private val lock = Mutex()

    /** Sends the copy when it's due. Throws [java.io.IOException] when the server couldn't take it, to be tried later. */
    suspend fun run(now: Long = System.currentTimeMillis()) = lock.withLock {
        if (!account.signedIn.value) return@withLock
        val c = login() ?: return@withLock
        if (c.kind == Credentials.Kind.Stalker) return@withLock
        withContext(Dispatchers.IO) {
            val id = ChannelShare.playlistId(c.server, c.username, c.playlistUrl)
            val channels =
                db.items().liveForSharing().map {
                    ChannelShare.Channel(
                        name = it.name,
                        group = it.groupName,
                        logo = it.logo,
                        epgId = it.tvgId,
                        number = it.number,
                        url = ChannelShare.hideLogin(it.url, c.username, c.password),
                    )
                }
            if (channels.isEmpty()) return@withContext
            val fingerprint = ChannelShare.fingerprint(id, channels)
            val sentAt = prefs.getLong(KEY_AT, 0)
            if (prefs.getString(KEY_FINGERPRINT, null) == fingerprint && now - sentAt in 0L until ChannelShare.REFRESH_MS) return@withContext
            var gzipped = write(id, channels, now, withGuide = true)
            // Too big with the guide (a huge playlist): the channels alone still open in VLC.
            if (gzipped.size > ChannelShare.MAX_GZIPPED_BYTES) gzipped = write(id, channels, now, withGuide = false)
            if (gzipped.size > ChannelShare.MAX_GZIPPED_BYTES) {
                AppLog.w(TAG, "The channel list is too big to share with the website (${channels.size} channels)")
                return@withContext
            }
            account.shareChannels(gzipped)
            prefs.edit {
                putString(KEY_FINGERPRINT, fingerprint)
                putLong(KEY_AT, now)
            }
            AppLog.i(TAG, "Shared ${channels.size} channels with the website (${gzipped.size / BYTES_PER_KB} KB)")
        }
    }

    private suspend fun write(
        id: String,
        channels: List<ChannelShare.Channel>,
        now: Long,
        withGuide: Boolean,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).bufferedWriter(Charsets.UTF_8).use { out ->
            ChannelShare.begin(out, id, channels, now)
            if (withGuide) {
                var first = true
                val from = now - ChannelShare.GUIDE_BEFORE_MS
                val to = now + ChannelShare.GUIDE_AFTER_MS
                for (ids in ChannelShare.guideIds(channels).chunked(GUIDE_CHUNK)) {
                    val shows = db.programmes().window(ids, from, to).map { ChannelShare.Show(it.channelId, it.start, it.stop, it.title) }
                    for ((channel, list) in shows.groupBy { it.channelId }) {
                        if (ChannelShare.guideEntry(out, first, channel, list, now)) first = false
                    }
                }
            }
            ChannelShare.end(out)
        }
        return bytes.toByteArray()
    }

    private companion object {
        const val TAG = "SharedChannels"
        const val KEY_FINGERPRINT = "shared_channels_fingerprint"
        const val KEY_AT = "shared_channels_at"

        // Under SQLite's 999 bound parameters, with room for the two times.
        const val GUIDE_CHUNK = 500
        const val BYTES_PER_KB = 1024
    }
}
