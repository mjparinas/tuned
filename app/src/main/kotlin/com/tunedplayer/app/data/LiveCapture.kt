package com.tunedplayer.app.data

import com.tunedplayer.app.core.HlsPlaylist
import com.tunedplayer.app.core.TsAlign
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Receives a live channel into [Sink] until a time, for a scheduled recording ([com.tunedplayer.app.work.RecordWorker]).
 * One connection at a time, as everything else: an MPEG-TS stream is one long request (from its first whole packet,
 * reconnecting if it drops), and an HLS channel is its playlist polled and its pieces fetched one after another, from
 * the live edge on, in the best quality the master playlist lists. Encrypted or fMP4 HLS can't be saved as one `.ts`
 * file, so it ends as [End.UNSUPPORTED]. Blocking: run it on an IO thread, and [cancel] it from anywhere.
 */
class LiveCapture(
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    enum class End { DONE, STOPPED, FAILED, UNSUPPORTED }

    fun interface Sink {
        fun write(
            data: ByteArray,
            offset: Int,
            length: Int,
        )
    }

    @Volatile private var call: Call? = null

    @Volatile private var cancelled = false

    /** Hangs up at once: the request in flight is cancelled and [capture] returns [End.STOPPED]. */
    fun cancel() {
        cancelled = true
        call?.cancel()
    }

    /** Records until [until]; [keepGoing] is asked between chunks, and false stops, as [cancel] does. */
    fun capture(
        url: String,
        userAgent: String,
        until: Long,
        keepGoing: () -> Boolean,
        sink: Sink,
    ): End {
        val run = Run(userAgent, until, keepGoing, sink)
        var failures = 0
        while (run.going()) {
            val result =
                try {
                    run.connect(url)
                } catch (_: IOException) {
                    null
                }
            when {
                result == End.UNSUPPORTED || result == End.DONE -> return result
                !run.going() -> break
                run.gotSince -> failures = 0
                else -> failures++
            }
            run.gotSince = false
            if (failures >= MAX_FAILURES) return End.FAILED
            sleep(RETRY_MS * (failures + 1))
        }
        return if (now() >= until) End.DONE else End.STOPPED
    }

    private inner class Run(
        private val userAgent: String,
        private val until: Long,
        private val keepGoing: () -> Boolean,
        private val sink: Sink,
    ) {
        /** Whether anything arrived since the last connection attempt; resets the count of failures. */
        var gotSince = false
        private var aligned = false
        private var lastSequence = Long.MIN_VALUE

        /** The master playlist lists only qualities that can't be saved as one file. */
        private var unsupported = false

        fun going() = !cancelled && now() < until && keepGoing()

        /** One connection: a TS stream until it ends, or an HLS channel until its playlist stops answering. */
        fun connect(url: String): End? = open(url).use { r ->
            if (!r.isSuccessful) return null
            val type = r.header("Content-Type").orEmpty().lowercase()
            if ("mpegurl" in type || HlsPlaylist.isPlaylist(r.request.url.encodedPath)) {
                val text = r.body.string().take(MAX_PLAYLIST_CHARS)
                return hls(text, r.request.url.toString())
            }
            stream(r)
            null
        }

        private fun stream(r: Response) {
            val input = r.body.byteStream()
            val chunk = ByteArray(CHUNK)
            while (going()) {
                val n = input.read(chunk)
                if (n < 0) break
                write(chunk, n)
            }
        }

        private fun write(
            chunk: ByteArray,
            n: Int,
        ) {
            if (!aligned) {
                val start = TsAlign.firstPacket(chunk, 0, n)
                if (start < 0) return
                aligned = true
                sink.write(chunk, start, n - start)
            } else {
                sink.write(chunk, 0, n)
            }
            gotSince = true
        }

        private fun hls(
            first: String,
            base: String,
        ): End? {
            val (firstText, link) = mediaPlaylist(first, base) ?: return if (unsupported) End.UNSUPPORTED else null
            var text: String? = firstText
            while (text != null && going()) {
                val media = HlsPlaylist.parse(text, link) as? HlsPlaylist.Media ?: break
                if (!media.keepable) return End.UNSUPPORTED
                take(media)
                if (media.ended) return End.DONE
                sleep((media.targetSeconds * HALF_SECOND_MS).toLong().coerceIn(MIN_POLL_MS, MAX_POLL_MS))
                text = fetchText(link)
            }
            return null
        }

        /** The media playlist to follow and its link: the playlist itself, or a master's best quality; null if unreadable. */
        private fun mediaPlaylist(
            text: String,
            base: String,
        ): Pair<String, String>? {
            val master = HlsPlaylist.parse(text, base) as? HlsPlaylist.Master ?: return text to base
            if (!master.keepable) {
                unsupported = true
                return null
            }
            val link = master.variants.maxByOrNull { it.bandwidth }?.uri ?: return null
            return fetchText(link)?.let { it to link }
        }

        /** The pieces not yet saved, from the live edge: what's already in the first window aired before the recording. */
        private fun take(media: HlsPlaylist.Media) {
            if (lastSequence == Long.MIN_VALUE) lastSequence = (media.segments.lastOrNull()?.sequence ?: 0) - 1
            for (piece in media.segments) {
                if (!going()) break
                if (piece.sequence <= lastSequence) continue
                piece(piece.uri)
                lastSequence = piece.sequence
            }
        }

        private fun piece(url: String) {
            open(url).use { r ->
                if (!r.isSuccessful) return
                val input = r.body.byteStream()
                val chunk = ByteArray(CHUNK)
                while (!cancelled) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    write(chunk, n)
                }
            }
        }

        private fun fetchText(url: String): String? = open(url).use { r ->
            if (r.isSuccessful) r.body.string().take(MAX_PLAYLIST_CHARS) else null
        }

        private fun open(url: String): Response {
            if (cancelled) throw IOException("Cancelled")
            val c =
                http.newCall(
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .build(),
                )
            call = c
            if (cancelled) c.cancel()
            return c.execute()
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val MAX_FAILURES = 5
        const val RETRY_MS = 2_000L
        const val MAX_PLAYLIST_CHARS = 1_000_000
        const val HALF_SECOND_MS = 500.0
        const val MIN_POLL_MS = 1_000L
        const val MAX_POLL_MS = 10_000L
    }
}
