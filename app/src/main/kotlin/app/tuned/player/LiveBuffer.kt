package app.tuned.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.tuned.core.LocalHls
import app.tuned.core.TsSegmenter
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

    val gate = Semaphore(1)

    val playlist: File get() = File(dir, PLAYLIST)

    /** Seconds of complete pieces on the device. */
    val seconds: Double get() = synchronized(lock) { pieces.sumOf { it.seconds } }

    /** Seconds received after the last complete piece. */
    val pendingSeconds: Double get() = synchronized(lock) { segmenter.pending }

    /** Starts afresh for another channel: everything kept so far is deleted. */
    fun reset(uri: Uri) = synchronized(lock) {
        stopRecording()
        closePiece()
        dir.listFiles()?.forEach { it.delete() }
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
    }

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

        override fun open(dataSpec: DataSpec): Long {
            beforeOpen()
            holding = gate.tryAcquire(GATE_WAIT_S, TimeUnit.SECONDS)
            keeping = isLiveTs(dataSpec.uri)
            if (keeping) {
                if (dataSpec.uri == source) reconnected() else reset(dataSpec.uri)
            }
            return upstream.open(dataSpec)
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val n = upstream.read(buffer, offset, length)
            if (keeping && n > 0) feed(buffer, offset, n)
            return n
        }

        override fun close() {
            try {
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
        const val PLAYLIST = "live.m3u8"
        const val GATE_WAIT_S = 5L

        /** A live MPEG-TS link (Xtream's `/live/…/id.ts`, or any `.ts`); HLS and files are left alone. */
        fun isLiveTs(uri: Uri): Boolean {
            val scheme = uri.scheme ?: return false
            if (scheme != "http" && scheme != "https") return false
            val path = uri.path.orEmpty().lowercase()
            if ("/timeshift/" in path) return false // a catch-up recording, which can seek already
            return !path.endsWith(".m3u8") && !path.endsWith(".mp4") && !path.endsWith(".mkv") && !path.endsWith(".m3u")
        }
    }
}
