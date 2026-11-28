package com.tunedplayer.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.tunedplayer.app.core.HlsPlaylist
import com.tunedplayer.app.core.LocalHls
import com.tunedplayer.app.core.Stalker
import com.tunedplayer.app.core.TsSegmenter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The live channel kept on the device, for pause and scrub (see [TsSegmenter] and [LiveTimeshift]). Bytes of the live
 * stream go in through [feed]; they come out as short `.ts` pieces in [dir] with a live HLS playlist, [playlist], that
 * the player can pause in, rewind and catch up. Pieces older than [keepMs] (or past [maxBytes]) are deleted as new
 * ones arrive.
 *
 * Only one connection to the provider: [gate] is held by whoever has the stream open, the player's own data source
 * ([Tee]) or the recorder that takes over once the player plays from the device.
 */
@OptIn(UnstableApi::class)
internal class LiveBuffer(
    val dir: File,
    private val keepMs: Long,
    private val maxBytes: Long,
) {
    private val lock = Any()
    private val pieces = ArrayDeque<LocalHls.Piece>()
    private val sizes = ArrayDeque<Long>()
    private var bytes = 0L
    private var sequence = 0L
    private var discontinuitySequence = 0L
    private var nextDiscontinuity = false
    private var out: FileOutputStream? = null
    private var segmenter = newSegmenter()
    private var recording: Recorder? = null

    /** Where a recording ([LiveRecorder]) gets the channel's bytes, on the same connection as the player. */
    interface Recorder {
        fun write(
            data: ByteArray,
            offset: Int,
            length: Int,
        )

        /** The channel changed or the player closed: the recording is over. */
        fun end()
    }

    /** The stream being kept, or null before the first one. */
    @Volatile
    var source: Uri? = null
        private set

    /**
     * The channel on screen, as the player last said ([LiveTimeshift.onChannelChanged]). An HLS channel's playlists
     * are all `.m3u8` links, so this is how a new channel is told from the same channel's next playlist.
     */
    @Volatile
    var channel: Uri? = null

    /** What an HLS channel's playlists said, or null for an MPEG-TS channel ([HlsPlaylist]). */
    private var hls: HlsState? = null

    private class HlsState {
        /** Until a playlist says otherwise; encrypted, fMP4 and split-audio channels aren't kept. */
        var keepable = true
        var sawMedia = false

        /** The last media playlist, by its real address, for the recorder to carry on from. */
        var media: String? = null

        /** Pieces in the last two media playlists, each with the playlist that listed it. */
        val listed = LinkedHashMap<String, String>()
        val fed = LinkedHashSet<String>()
        var lastMedia: String? = null
    }

    val isHls: Boolean get() = synchronized(lock) { hls != null }

    /** Whether this channel is being kept: always for MPEG-TS, for HLS once its playlists show plain pieces. */
    val keeping: Boolean get() = synchronized(lock) { hls?.let { it.keepable && it.sawMedia } ?: (source != null) }

    /** The HLS channel's media playlist to fetch next, by its real address. */
    val hlsMedia: String? get() = synchronized(lock) { hls?.media }

    val gate = Semaphore(1)

    val playlist: File get() = File(dir, PLAYLIST)

    /** Seconds of complete pieces on the device. */
    val seconds: Double get() = synchronized(lock) { pieces.sumOf { it.seconds } }

    /** Seconds received after the last complete piece. */
    val pendingSeconds: Double get() = synchronized(lock) { segmenter.pending }

    /** Starts afresh for another channel: everything kept so far is deleted. */
    fun reset(
        uri: Uri,
        isHls: Boolean = false,
    ) = synchronized(lock) {
        hls = if (isHls) HlsState() else null
        stopRecording()
        closePiece()
        discardOld()
        dir.mkdirs()
        pieces.clear()
        sizes.clear()
        bytes = 0
        discontinuitySequence = 0
        nextDiscontinuity = false
        segmenter = newSegmenter()
        source = uri
    }

    /** The same channel, on a new connection: the stream's clock may jump, so the next piece says so. */
    fun reconnected() = synchronized(lock) {
        closePiece()
        segmenter = newSegmenter()
        nextDiscontinuity = pieces.isNotEmpty()
    }

    fun feed(
        data: ByteArray,
        offset: Int,
        length: Int,
    ) = synchronized(lock) {
        recording?.write(data, offset, length)
        // Pause and rewind off: the stream still passes through, for recording, but nothing is kept.
        if (keepMs > 0) segmenter.feed(data, offset, length)
    }

    /**
     * An HLS playlist of this channel, as read from [base] (its real address). A master's qualities aren't needed: any
     * media playlist the player opens for this channel is one of them. A master that turns up for another link is
     * another channel: the player changed channel before it said so.
     */
    fun sawPlaylist(
        link: Uri,
        base: String,
        text: String,
    ) = synchronized(lock) {
        val state = hls ?: return@synchronized
        when (val parsed = HlsPlaylist.parse(text, base)) {
            is HlsPlaylist.Master -> {
                if (link != source) {
                    reset(link, isHls = true)
                    hls?.keepable = parsed.keepable
                } else if (!parsed.keepable) {
                    state.keepable = false
                }
            }

            is HlsPlaylist.Media -> {
                // A subtitle rendition's playlist lists text, not pieces of the picture.
                if (parsed.subtitles) return@synchronized
                if (!parsed.keepable) state.keepable = false
                state.sawMedia = true
                state.media = base
                parsed.segments.forEach { state.listed[it.uri] = base }
                while (state.listed.size > MAX_LISTED) state.listed.remove(state.listed.keys.first())
            }

            null -> {}
        }
    }

    /** Whether [uri] is listed in this HLS channel's playlists. */
    fun isPiece(uri: String): Boolean = synchronized(lock) { hls?.listed?.containsKey(uri) == true }

    /**
     * Whether a request for [uri] is one of this HLS channel's pieces not kept yet; it then counts as kept. A piece
     * from another quality than the last one marks a jump, as a reconnect does.
     */
    fun takePiece(uri: String): Boolean = synchronized(lock) {
        val state = hls ?: return@synchronized false
        if (!state.keepable) return@synchronized false
        val media = state.listed[uri] ?: return@synchronized false
        if (!state.fed.add(uri)) return@synchronized false
        while (state.fed.size > MAX_LISTED) state.fed.remove(state.fed.first())
        if (state.lastMedia != null && state.lastMedia != media) {
            closePiece()
            segmenter = newSegmenter()
            nextDiscontinuity = pieces.isNotEmpty()
        }
        state.lastMedia = media
        true
    }

    /** Sends the channel's bytes to [r] from now on, until [stopRecording] or the channel changes. */
    fun startRecording(r: Recorder) = synchronized(lock) {
        stopRecording()
        recording = r
    }

    fun stopRecording() = synchronized(lock) {
        val r = recording ?: return@synchronized
        recording = null
        r.end()
    }

    fun release() = synchronized(lock) {
        stopRecording()
        closePiece()
        dir.deleteRecursively()
        leftovers().forEach { it.deleteRecursively() }
    }

    /**
     * The last channel's pieces (up to half an hour of them) leave the folder at once and are deleted on a quiet
     * thread, so the next channel's stream opens without waiting on the disk (Melvin, 2026-11-28: faster channel
     * zapping). It used to delete them one by one on the stream's own thread before opening the new one.
     */
    private fun discardOld() {
        val old = dir.listFiles().orEmpty()
        if (old.isEmpty()) return
        val trash = File(dir.parentFile, "${dir.name}$TRASH${System.nanoTime()}")
        if (dir.renameTo(trash)) {
            trashCan.execute { trash.deleteRecursively() }
        } else {
            old.forEach { it.delete() }
        }
    }

    /** Folders of old pieces a closed player left behind. */
    private fun leftovers(): List<File> = dir.parentFile
        ?.listFiles { f -> f.name.startsWith(dir.name + TRASH) }
        .orEmpty()
        .toList()

    private fun newSegmenter() = TsSegmenter(
        sink =
            object : TsSegmenter.Sink {
                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    val stream = out ?: FileOutputStream(File(dir, "$sequence.ts")).also { out = it }
                    stream.write(bytes, offset, length)
                }

                override fun cut(seconds: Double) = finishPiece(seconds)
            },
    )

    private fun finishPiece(seconds: Double) {
        val stream = out ?: return
        stream.close()
        out = null
        val file = File(dir, "$sequence.ts")
        pieces.addLast(LocalHls.Piece(sequence, file.name, seconds, nextDiscontinuity))
        sizes.addLast(file.length())
        bytes += file.length()
        nextDiscontinuity = false
        sequence++
        while (pieces.size > 1 && (pieces.sumOf { it.seconds } * 1_000 > keepMs || bytes > maxBytes)) {
            val gone = pieces.removeFirst()
            bytes -= sizes.removeFirst()
            if (gone.discontinuity) discontinuitySequence++
            File(dir, gone.file).delete()
        }
        writePlaylist()
    }

    /** An unfinished piece is dropped: it may end mid-packet. */
    private fun closePiece() {
        out?.close()
        out = null
        File(dir, "$sequence.ts").delete()
    }

    private fun writePlaylist() {
        val tmp = File(dir, "$PLAYLIST.tmp")
        tmp.writeText(LocalHls.playlist(pieces.toList(), discontinuitySequence))
        tmp.renameTo(playlist)
    }

    /**
     * The player's own data source for live channels: passes the stream through untouched and keeps a copy here. It
     * holds [gate] while open, so the recorder can't open a second connection at the same time.
     */
    inner class Tee(
        private val upstream: DataSource,
        /** Stops the recorder, if it runs, before this opens its connection. */
        private val beforeOpen: () -> Unit,
    ) : DataSource {
        private var holding = false
        private var keeping = false

        /** An HLS channel's playlist being read, kept to learn its pieces ([sawPlaylist]); null otherwise. */
        private var playlistText: java.io.ByteArrayOutputStream? = null
        private var playlistLink: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            beforeOpen()
            holding = gate.tryAcquire(GATE_WAIT_S, TimeUnit.SECONDS)
            val uri = dataSpec.uri
            playlistText = null
            keeping = false
            when {
                // A piece of the HLS channel (its links end in .ts too): the whole of it, never part of one.
                isPiece(uri.toString()) -> {
                    keeping = dataSpec.position == 0L && takePiece(uri.toString())
                }

                isLiveTs(uri) -> {
                    keeping = true
                    if (uri == source) reconnected() else reset(uri)
                }

                isLiveHls(uri) -> {
                    // The channel's own link (a new channel, or the first), or another playlist of the same channel.
                    if (!isHls || (uri == channel && uri != source)) reset(uri, isHls = true)
                    playlistText = java.io.ByteArrayOutputStream()
                    playlistLink = uri
                }
            }
            return upstream.open(dataSpec)
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val n = upstream.read(buffer, offset, length)
            if (n > 0) {
                if (keeping) feed(buffer, offset, n)
                playlistText?.let { if (it.size() < MAX_PLAYLIST_BYTES) it.write(buffer, offset, n) }
            }
            return n
        }

        override fun close() {
            try {
                val text = playlistText
                val link = playlistLink
                playlistText = null
                if (text != null && link != null) {
                    sawPlaylist(link, (upstream.uri ?: link).toString(), text.toString(Charsets.UTF_8.name()))
                }
                upstream.close()
            } finally {
                if (holding) gate.release()
                holding = false
            }
        }

        override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    }

    companion object {
        private const val TRASH = "-old-"

        /** One quiet thread for deleting old pieces, so a run of channel changes never piles up threads. */
        private val trashCan =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "LiveBufferTrash").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }

        const val PLAYLIST = "live.m3u8"
        const val GATE_WAIT_S = 5L
        private const val MAX_LISTED = 64
        private const val MAX_PLAYLIST_BYTES = 1024 * 1024

        /**
         * A live HLS link (`.m3u8`), or a portal's live channel whose command names HLS: kept piece by piece when its
         * playlists show plain MPEG-TS pieces ([HlsPlaylist]).
         */
        fun isLiveHls(uri: Uri): Boolean {
            val scheme = uri.scheme ?: return false
            if (scheme == "stalker") return Stalker.parse(uri.toString())?.let { it.type == Stalker.LIVE && Stalker.isHls(it.cmd) } == true
            if (scheme != "http" && scheme != "https") return false
            if ("/timeshift/" in uri.path.orEmpty().lowercase()) return false
            return HlsPlaylist.isPlaylist(uri.toString())
        }

        /** A live MPEG-TS link (Xtream's `/live/…/id.ts`, or any `.ts`); HLS and files are left alone. */
        fun isLiveTs(uri: Uri): Boolean {
            val scheme = uri.scheme ?: return false
            // A portal's live channel, whose real link comes as it opens: MPEG-TS unless its command names HLS.
            if (scheme == "stalker") return Stalker.parse(uri.toString())?.let { it.type == Stalker.LIVE && !Stalker.isHls(it.cmd) } == true
            if (scheme != "http" && scheme != "https") return false
            val path = uri.path.orEmpty().lowercase()
            if ("/timeshift/" in path) return false // a catch-up recording, which can seek already
            return !path.endsWith(".m3u8") && !path.endsWith(".mp4") && !path.endsWith(".mkv") && !path.endsWith(".m3u")
        }
    }
}
