package app.tuned.player

import android.media.MediaExtractor
import android.media.MediaFormat
import app.tuned.core.CastCodecs
import app.tuned.core.CastHls
import app.tuned.core.FileServer
import app.tuned.core.Fmp4
import app.tuned.core.NalUnits
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/**
 * A saved film or episode streamed to a Chromecast as HLS made on the fly ([CastHls]; Melvin, 2026-11-18: "can we do
 * on the fly encoding instead of doing the full conversion first?"). The playlist is ready in well under a second,
 * since it needs only the key frames; each piece of about six seconds is cut from the file when the TV asks for it,
 * the pictures copied as they are and the sound copied when it is stereo AAC, otherwise turned into stereo AAC
 * ([SoundPieces]). Nothing is written to the phone's storage.
 */
internal class CastStream private constructor(
    private val source: File,
    private val videoIndex: Int,
    private val video: Fmp4.Track,
    private val audioIndex: Int?,
    private val audio: Fmp4.Track?,
    private val newSound: SoundPieces?,
    private val starts: List<Long>,
    private val durationUs: Long,
) : FileServer.Made,
    Closeable {
    private val lock = Any()
    private val pictures = extractor(source, videoIndex)
    private val sound = if (newSound == null) audioIndex?.let { extractor(source, it) } else null
    private val init = Fmp4.init(listOfNotNull(video, audio))
    private val playlist = CastHls.playlist(starts, durationUs).toByteArray()
    private val recent = LinkedHashMap<Int, ByteArray>()
    private var buffer = ByteBuffer.allocateDirect(START_BUFFER)

    override fun type(file: String): String = CastHls.mimeFor(file)

    override fun bytes(file: String): ByteArray? = when (file) {
        CastHls.PLAYLIST -> playlist
        CastHls.INIT -> init
        else -> {
            CastHls.pieceIndex(file)?.takeIf { it < starts.size }?.let { k ->
                // A failure is logged and the TV told there is no such piece, rather than stopping the server.
                attempt(TAG, "Couldn't make piece $k of ${source.name}") { piece(k) }.getOrNull()
            }
        }
    }

    /** Piece [k], made now; the last few are kept, as a TV can ask for the same one twice. */
    private fun piece(k: Int): ByteArray = synchronized(lock) {
        recent[k]?.let { return it }
        val from = starts[k]
        val to = starts.getOrNull(k + 1) ?: Long.MAX_VALUE
        val runs = ArrayList<Pair<Fmp4.Track, List<Fmp4.Sample>>>()
        runs += video to pictures(from, to)
        if (audio != null) {
            // The first piece takes the sound from the very start, even before the first picture.
            val soundFrom = if (k == 0) 0L else from
            runs += audio to (newSound?.frames(k, soundFrom, to) ?: copiedSound(soundFrom, to))
        }
        val bytes = Fmp4.segment(k + 1, runs, if (to == Long.MAX_VALUE) durationUs else to)
        recent[k] = bytes
        while (recent.size > KEEP_PIECES) recent.remove(recent.keys.first())
        bytes
    }

    /** The pictures from the key frame at [from] up to the key frame at [to], in decode order. */
    private fun pictures(
        from: Long,
        to: Long,
    ): List<Fmp4.Sample> {
        val ex = pictures
        ex.seekTo(from, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        // An extractor that lands on an earlier key frame is walked up to this piece's.
        while (ex.sampleTime >= 0 && !(ex.isKey() && ex.sampleTime >= from - TOLERANCE_US)) {
            if (!ex.advance()) break
        }
        val out = ArrayList<Fmp4.Sample>()
        while (true) {
            val time = ex.sampleTime
            if (time < 0) break
            val key = ex.isKey()
            if (out.isNotEmpty() && key && time >= to - TOLERANCE_US) break
            val data = read(ex) ?: break
            out += Fmp4.Sample(NalUnits.toLengthPrefixed(data), time, key)
            ex.advance()
        }
        return out
    }

    /** AAC frames copied as they are, those that start in [from, to). */
    private fun copiedSound(
        from: Long,
        to: Long,
    ): List<Fmp4.Sample> {
        val ex = sound ?: return emptyList()
        ex.seekTo(from, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val out = ArrayList<Fmp4.Sample>()
        while (true) {
            val time = ex.sampleTime
            if (time < 0 || time >= to) break
            if (time >= from) out += Fmp4.Sample(read(ex) ?: break, time, key = true)
            ex.advance()
        }
        return out
    }

    /** The current sample's bytes, growing the buffer for a picture bigger than any before it. */
    private fun read(ex: MediaExtractor): ByteArray? {
        while (true) {
            buffer.clear()
            val size =
                try {
                    ex.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    if (buffer.capacity() >= MAX_BUFFER) throw e
                    buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                    continue
                }
            if (size < 0) return null
            val data = ByteArray(size)
            buffer.position(0)
            buffer.get(data, 0, size)
            return data
        }
    }

    override fun close() {
        synchronized(lock) {
            attempt(TAG, "Couldn't close the picture reader") { pictures.release() }
            sound?.let { s -> attempt(TAG, "Couldn't close the sound reader") { s.release() } }
            newSound?.close()
            recent.clear()
        }
    }

    companion object {
        private const val TAG = "Cast"
        private const val TOLERANCE_US = 1_000L
        private const val KEEP_PIECES = 3
        private const val START_BUFFER = 1 shl 20
        private const val MAX_BUFFER = 64 shl 20

        /** Pieces are cut at key frames found every this often, so finding them takes one seek per piece. */
        private const val STEP_US = CastHls.TARGET_US
        private const val MIN_PIECE_US = 1_000_000L
        private const val TRACK_VIDEO = 1
        private const val TRACK_AUDIO = 2
        private const val VIDEO_TIMESCALE = 90_000

        /**
         * A stream for [source], or null when its video can't go in an MP4 (the caller then sends the file as it is).
         * Reads the tracks and finds a key frame about every six seconds, so it takes a moment: call it off the main
         * thread.
         */
        fun open(source: File): CastStream? {
            val started = System.currentTimeMillis()
            val ex = extractor(source, track = null)
            try {
                val formats = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                // Every detail Android reads (profile, level, size, colour, channels), so a TV's refusal can be matched to it.
                formats.forEachIndexed { i, f -> AppLog.i(TAG) { "${source.name} track $i: ${f.toString().take(MAX_FORMAT_CHARS)}" } }
                val videoIndex = formats.indexOfFirst { mime(it).startsWith("video/") }.takeIf { it >= 0 } ?: return null
                val videoFormat = formats[videoIndex]
                if (!CastCodecs.streams(mime(videoFormat))) {
                    AppLog.i(TAG) { "${source.name}: ${mime(videoFormat)} can't be streamed in pieces, so it goes as it is" }
                    return null
                }
                val video = videoTrack(videoFormat) ?: return null
                val audioIndex = formats.indexOfFirst { mime(it).startsWith("audio/") }.takeIf { it >= 0 }
                val audioFormat = audioIndex?.let { formats[it] }
                val channels = audioFormat?.int(MediaFormat.KEY_CHANNEL_COUNT)
                var newSound: SoundPieces? = null
                val audio =
                    when {
                        audioIndex == null || audioFormat == null -> {
                            null
                        }

                        CastCodecs.copiesSound(mime(audioFormat), channels) && audioFormat.containsKey(CSD_0) -> {
                            val config = audioFormat.csd(CSD_0)
                            val rate = audioFormat.int(MediaFormat.KEY_SAMPLE_RATE) ?: DEFAULT_RATE
                            Fmp4.Track(TRACK_AUDIO, false, rate, Fmp4.mp4a(channels ?: 2, rate, config))
                        }

                        else -> {
                            val pieces = SoundPieces(source, audioIndex, audioFormat)
                            val (rate, out) = pieces.start()
                            newSound = pieces
                            Fmp4.Track(TRACK_AUDIO, false, rate, Fmp4.mp4a(out, rate, CastCodecs.aacConfig(rate, out)))
                        }
                    }
                val durationUs = videoFormat.long(MediaFormat.KEY_DURATION) ?: 0L
                val keys = keyFrames(source, videoIndex, durationUs)
                val starts = CastHls.starts(keys, MIN_PIECE_US)
                AppLog.i(TAG) {
                    "${source.name}: streaming ${mime(videoFormat)} with " +
                        (if (newSound != null) "sound turned into stereo AAC" else "sound as it is (${audioFormat?.let { mime(it) }})") +
                        ", ${starts.size} pieces, ready in ${System.currentTimeMillis() - started} ms"
                }
                return CastStream(
                    source,
                    videoIndex,
                    video,
                    audioIndex,
                    audio,
                    newSound,
                    starts,
                    maxOf(durationUs, (starts.lastOrNull() ?: 0L) + MIN_PIECE_US),
                )
            } finally {
                ex.release()
            }
        }

        private fun videoTrack(format: MediaFormat): Fmp4.Track? {
            val csd0 = format.csd(CSD_0)
            if (csd0.isEmpty()) {
                AppLog.w(TAG, "The video has no setup data, so it can't be streamed in pieces")
                return null
            }
            val csd1 = if (format.containsKey(CSD_1)) format.csd(CSD_1) else null
            val width = format.int(MediaFormat.KEY_WIDTH) ?: 0
            val height = format.int(MediaFormat.KEY_HEIGHT) ?: 0
            val entry =
                attempt(TAG, "Couldn't read the video's setup") {
                    if (mime(format) == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                        Fmp4.hvc1(width, height, NalUnits.hvcC(csd0, csd1))
                    } else {
                        Fmp4.avc1(width, height, NalUnits.avcC(csd0, csd1))
                    }
                }.getOrNull() ?: return null
            return Fmp4.Track(TRACK_VIDEO, true, VIDEO_TIMESCALE, entry, width, height)
        }

        /** The key frame at or before every [STEP_US], one seek each, rather than reading every picture. */
        private fun keyFrames(
            source: File,
            videoIndex: Int,
            durationUs: Long,
        ): List<Long> {
            val ex = extractor(source, videoIndex)
            try {
                val keys = ArrayList<Long>()
                var t = 0L
                while (durationUs <= 0 || t <= durationUs) {
                    ex.seekTo(t, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val at = ex.sampleTime
                    if (at < 0) break
                    if (keys.isEmpty() || at > keys.last()) keys += at
                    // A minute without a new key frame is the end of a file that doesn't say how long it is.
                    if (t - keys.last() > END_GAP_US) break
                    t += STEP_US
                }
                return keys
            } finally {
                ex.release()
            }
        }

        private const val END_GAP_US = 60_000_000L
        private const val MAX_FORMAT_CHARS = 600
        private const val DEFAULT_RATE = 48_000
        private const val CSD_0 = "csd-0"
        private const val CSD_1 = "csd-1"

        private fun extractor(
            source: File,
            track: Int?,
        ): MediaExtractor = MediaExtractor().apply {
            setDataSource(source.path)
            track?.let { selectTrack(it) }
        }

        private fun mime(format: MediaFormat): String = format.getString(MediaFormat.KEY_MIME).orEmpty()

        private fun MediaFormat.int(key: String): Int? = if (containsKey(key)) getInteger(key) else null

        private fun MediaFormat.long(key: String): Long? = if (containsKey(key)) getLong(key) else null

        private fun MediaFormat.csd(key: String): ByteArray {
            val b = getByteBuffer(key) ?: return ByteArray(0)
            val copy = b.duplicate()
            copy.position(0)
            return ByteArray(copy.remaining()).also { copy.get(it) }
        }

        private fun MediaExtractor.isKey(): Boolean = sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
    }
}
