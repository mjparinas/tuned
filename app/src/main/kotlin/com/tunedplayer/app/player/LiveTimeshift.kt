package com.tunedplayer.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import com.tunedplayer.app.core.HlsPlaylist
import com.tunedplayer.app.core.Stalker
import com.tunedplayer.app.diagnostics.loggingHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pause and scrub live TV (Melvin, 2026-11-17: "Can you add pause and scrub live TV support?").
 *
 * While a live channel plays, [LiveBuffer] keeps the last few minutes on the device, straight from the player's own
 * stream, so starting a channel is no slower. Pausing switches the channel's spot in the list to the copy on the device
 * at the moment on screen: from then on it plays from the device and can be paused, rewound and fast-forwarded back to
 * live with the usual controls, while a recorder carries on receiving the channel. The player's stream closes before
 * the recorder opens its own, so there is still one connection. Changing channel puts the live link back.
 */
@OptIn(UnstableApi::class)
internal class LiveTimeshift(
    private val context: Context,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val userAgent: () -> String,
    /** How much to keep, from Settings › Player › Pause live TV; 0 turns it off. */
    private val keepMinutes: () -> Int,
    /** A Stalker portal's channel turned into its stream (`data/StalkerLinks`); blocks, and runs on the recorder's thread. */
    private val resolve: (String) -> String = { it },
) {
    private var buffer: LiveBuffer? = null

    @Volatile
    private var recorder: Job? = null

    @Volatile
    private var call: Call? = null

    /** The spot in the list playing from the device, and the live item it replaced. */
    private var shifted: Pair<Int, MediaItem>? = null

    val enabled: Boolean get() = keepMinutes() > 0

    /**
     * Wraps the player's network data source for live channels so the stream is kept as it plays. With pause and
     * rewind off it still passes through, keeping nothing, so the channel can be recorded ([LiveRecorder]).
     */
    fun factory(upstream: DataSource.Factory): DataSource.Factory {
        buffer?.release()
        val b = LiveBuffer(File(context.cacheDir, DIR), keepMinutes() * MINUTE_MS, MAX_BYTES).also { buffer = it }
        return DataSource.Factory { b.Tee(upstream.createDataSource(), beforeOpen = ::stopRecorder) }
    }

    /**
     * Pause on a live channel: play the copy on the device instead, paused where the picture is. False when there is
     * nothing kept yet (the first few seconds), so the player just pauses.
     */
    fun shift(player: ExoPlayer): Boolean {
        val b = buffer ?: return false
        if (shifted != null) return false
        val current = player.currentMediaItem ?: return false
        val source = b.source ?: return false
        if (current.localConfiguration?.uri != source || !b.keeping) return false
        val kept = b.seconds
        if (kept < MIN_SECONDS) return false
        // The player shows what arrived a little while ago: its buffer is ahead of the picture.
        val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1_000.0
        val atMs = ((kept + b.pendingSeconds - ahead) * 1_000).toLong().coerceIn(0, (kept * 1_000).toLong())
        val index = player.currentMediaItemIndex
        val local =
            current
                .buildUpon()
                .setUri(Uri.fromFile(b.playlist))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                // Stay where the person paused: no speeding up to catch the live edge.
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration
                        .Builder()
                        .setMinPlaybackSpeed(1f)
                        .setMaxPlaybackSpeed(1f)
                        .build(),
                ).build()
        shifted = index to current
        player.playWhenReady = false
        player.replaceMediaItem(index, local)
        player.seekTo(index, atMs)
        player.prepare()
        record(b, source)
        return true
    }

    /** Playing the channel from the device, behind what is airing now (after a pause). */
    val behind: Boolean get() = shifted != null

    /**
     * Jump to live: the live link goes back in its spot and plays from what is airing now. The recorder stops before
     * the player reconnects, so there is still one connection, and what was kept stays for the next pause.
     */
    fun goLive(player: ExoPlayer): Boolean {
        val (index, original) = shifted ?: return false
        if (player.currentMediaItemIndex != index) return false
        shifted = null
        stopRecorder()
        player.replaceMediaItem(index, original)
        player.seekToDefaultPosition(index)
        player.prepare()
        player.playWhenReady = true
        return true
    }

    /** The player moved on: if it left the channel playing from the device, put its live link back. */
    fun onChannelChanged(player: ExoPlayer) {
        // Played from the device, the channel's spot holds the local playlist: the channel is still the one it replaced.
        buffer?.channel = shifted?.second?.localConfiguration?.uri ?: player.currentMediaItem?.localConfiguration?.uri
        val (index, original) = shifted ?: return
        if (player.currentMediaItemIndex == index && player.currentMediaItem?.mediaId == original.mediaId) return
        shifted = null
        stopRecorder()
        if (index < player.mediaItemCount) player.replaceMediaItem(index, original)
    }

    /** Keeps receiving the channel into [b] while the player plays from the device, reconnecting if it drops. */
    private fun record(
        b: LiveBuffer,
        source: Uri,
    ) {
        recorder =
            scope.launch(Dispatchers.IO + loggingHandler("LiveTimeshift")) {
                // Wait for the player's own stream to close: never two connections.
                if (!b.gate.tryAcquire(GATE_WAIT_S, TimeUnit.SECONDS)) return@launch
                try {
                    var failures = 0
                    while (isActive && failures < MAX_FAILURES) {
                        val got = if (b.isHls) receiveHls(b, source) else receive(b, source)
                        if (got) failures = 0 else failures++
                        if (isActive) delay(RETRY_MS * (failures + 1))
                    }
                } finally {
                    b.gate.release()
                }
            }
    }

    /** One connection's worth of the stream; true if anything arrived. */
    private fun receive(
        b: LiveBuffer,
        source: Uri,
    ): Boolean {
        var got = false
        try {
            val request =
                Request
                    .Builder()
                    .url(resolve(source.toString()))
                    .header("User-Agent", userAgent())
                    .build()
            val c = http.newCall(request).also { call = it }
            c.execute().use { r ->
                if (!r.isSuccessful) return false
                b.reconnected()
                val input = r.body.byteStream()
                val chunk = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    b.feed(chunk, 0, n)
                    got = true
                }
            }
        } catch (_: IOException) {
            // Dropped or cancelled: the loop decides whether to try again.
        }
        return got
    }

    /**
     * An HLS channel while the player plays from the device: its media playlist, then each piece not kept yet, one
     * request at a time, and again as the window moves. A playlist link that stops working (an expired token) is
     * found again from the channel's own link. True if any piece arrived.
     */
    private suspend fun receiveHls(
        b: LiveBuffer,
        source: Uri,
    ): Boolean {
        var got = false
        var media = b.hlsMedia
        var refound = false
        while (media != null && currentCoroutineContext().isActive) {
            val text = fetchText(media, source)
            if (text == null) {
                // Tried once more from the channel's own link; a second miss gives up for now.
                media = if (refound) null else refind(source, media)
                refound = true
                continue
            }
            refound = false
            b.sawPlaylist(source, text.second, text.first)
            val parsed = HlsPlaylist.parse(text.first, text.second) as? HlsPlaylist.Media ?: break
            for (piece in parsed.segments) {
                if (currentCoroutineContext().isActive && b.takePiece(piece.uri) && fetchPiece(piece.uri, source, b)) got = true
            }
            if (parsed.ended) break
            delay((parsed.targetSeconds * HALF_SECOND_MS).toLong().coerceIn(MIN_POLL_MS, MAX_POLL_MS))
        }
        return got
    }

    /** The channel's own link again, and the quality the player was on (or the first one). */
    private fun refind(
        source: Uri,
        before: String,
    ): String? {
        val (text, base) = fetchText(resolve(source.toString()), source) ?: return null
        return when (val parsed = HlsPlaylist.parse(text, base)) {
            is HlsPlaylist.Master -> {
                val path = before.substringBefore('?')
                (parsed.variants.firstOrNull { it.uri.substringBefore('?') == path } ?: parsed.variants.firstOrNull())?.uri
            }

            is HlsPlaylist.Media -> {
                base
            }

            null -> {
                null
            }
        }
    }

    /** A playlist's text and the address it came from (after any redirect), or null when it doesn't answer. */
    private fun fetchText(
        url: String,
        source: Uri,
    ): Pair<String, String>? = try {
        val c = http.newCall(request(url, source)).also { call = it }
        c.execute().use { r -> if (r.isSuccessful) r.body.string().take(MAX_PLAYLIST_CHARS) to r.request.url.toString() else null }
    } catch (_: IOException) {
        null
    }

    private fun fetchPiece(
        url: String,
        source: Uri,
        b: LiveBuffer,
    ): Boolean = try {
        val c = http.newCall(request(url, source)).also { call = it }
        c.execute().use { r ->
            if (!r.isSuccessful) return@use false
            val input = r.body.byteStream()
            val chunk = ByteArray(CHUNK)
            var any = false
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                b.feed(chunk, 0, n)
                any = true
            }
            any
        }
    } catch (_: IOException) {
        false
    }

    private fun request(
        url: String,
        source: Uri,
    ) = Request
        .Builder()
        .url(url)
        // Portals' stream servers often serve only what says it is a MAG box, as the player's own requests do.
        .header("User-Agent", if (source.scheme == "stalker") Stalker.USER_AGENT else userAgent())
        .build()

    private fun stopRecorder() {
        recorder?.cancel()
        recorder = null
        call?.cancel()
        call = null
    }

    /** The live channel's stream as it arrives, while the player is on it, live or paused; null for HLS, files or catch-up. */
    fun stream(uri: Uri?): LiveBuffer? = buffer?.takeIf {
        uri != null && it.keeping &&
            (it.source == uri || uri == Uri.fromFile(it.playlist))
    }

    fun release() {
        stopRecorder()
        buffer?.release()
        buffer = null
    }

    private companion object {
        const val DIR = "live"
        const val MINUTE_MS = 60_000L

        /** Kept pieces never take more than this, whatever the minutes setting. */
        const val MAX_BYTES = 1024L * 1024 * 1024
        const val MIN_SECONDS = 4.0
        const val GATE_WAIT_S = 10L
        const val MAX_FAILURES = 5
        const val RETRY_MS = 1_500L
        const val CHUNK = 64 * 1024
        const val HALF_SECOND_MS = 500.0
        const val MIN_POLL_MS = 1_000L
        const val MAX_POLL_MS = 10_000L
        const val MAX_PLAYLIST_CHARS = 1024 * 1024
    }
}
