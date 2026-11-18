package app.tuned.player

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import app.tuned.core.CastCodecs
import app.tuned.core.Fmp4
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sound of a saved file turned into stereo AAC piece by piece, for [CastStream]: Dolby, DTS and surround AAC,
 * which a Chromecast can't play or (on Melvin's Shield, 2026-11-18) refused, are decoded and encoded again, with
 * surround mixed for two speakers ([CastCodecs.downmix]). The encoder carries on from one piece to the next, so the
 * sound has no seams while the TV plays straight through; a piece out of order (the TV seeked) starts afresh just
 * before it.
 */
internal class SoundPieces(
    source: File,
    private val track: Int,
    private val format: MediaFormat,
) : Closeable {
    private val ex = MediaExtractor().apply {
        setDataSource(source.path)
        selectTrack(track)
    }
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private val pcm = ArrayDeque<ByteArray>()
    private var pcmBytes = 0
    private var floatPcm = false
    private var inChannels = 2
    private var outChannels = 2
    private var sampleRate = 0
    private var framesIn = 0L
    private var startUs = -1L
    private var extractorDone = false
    private var decoderDone = false
    private var encoderInputDone = false
    private var encodedDone = false

    /** Encoded frames not yet handed out: the first one past the last piece's end. */
    private val waiting = ArrayDeque<Fmp4.Sample>()

    /** The piece the encoder is positioned for, or -1 after a fresh start. */
    private var nextPiece = 0

    /**
     * Starts the decoder and encoder from the beginning and returns the sample rate and channel count the encoder
     * makes, which the init segment declares.
     */
    fun start(): Pair<Int, Int> {
        restart(0)
        var spins = 0
        while (encoder == null) {
            step()
            check(++spins < MAX_SPINS) { "The sound decoder never started" }
        }
        nextPiece = 0
        return sampleRate to outChannels
    }

    /** The encoded frames that start in [fromUs, toUs), for piece [piece]. */
    fun frames(
        piece: Int,
        fromUs: Long,
        toUs: Long,
    ): List<Fmp4.Sample> {
        if (piece != nextPiece) restart(fromUs)
        val out = ArrayList<Fmp4.Sample>()
        var spins = 0
        while (true) {
            while (waiting.isNotEmpty()) {
                val next = waiting.first()
                if (next.timeUs >= toUs) {
                    nextPiece = piece + 1
                    return out
                }
                waiting.removeFirst()
                if (next.timeUs >= fromUs) out += next
            }
            if (encodedDone) break
            if (step()) {
                spins = 0
            } else {
                check(++spins < MAX_SPINS) { "The sound stopped coming" }
            }
        }
        nextPiece = piece + 1
        return out
    }

    /** Drops what the codecs hold and starts reading a little before [fromUs], so the first frame is whole. */
    private fun restart(fromUs: Long) {
        releaseCodecs()
        pcm.clear()
        pcmBytes = 0
        waiting.clear()
        framesIn = 0
        startUs = -1
        extractorDone = false
        decoderDone = false
        encoderInputDone = false
        encodedDone = false
        ex.seekTo((fromUs - PRE_ROLL_US).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder =
            MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME).orEmpty()).apply {
                configure(format, null, null, 0)
                start()
            }
        nextPiece = -1
    }

    /** One turn of the pipeline; true when anything moved. */
    private fun step(): Boolean {
        val fed = feedDecoder()
        val decoded = drainDecoder()
        val encoded = feedEncoder()
        val drained = drainEncoder()
        return fed || decoded || encoded || drained
    }

    private fun feedDecoder(): Boolean {
        val dec = decoder ?: return false
        if (extractorDone || pcmBytes > MAX_PCM_BYTES) return false
        val index = dec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        val buffer = requireNotNull(dec.getInputBuffer(index))
        val size = ex.readSampleData(buffer, 0)
        if (size < 0) {
            dec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            extractorDone = true
        } else {
            dec.queueInputBuffer(index, 0, size, ex.sampleTime.coerceAtLeast(0), 0)
            ex.advance()
        }
        return true
    }

    private fun drainDecoder(): Boolean {
        val dec = decoder ?: return false
        if (decoderDone) return false
        val index = dec.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                startEncoder(dec.outputFormat)
                return true
            }

            index >= 0 -> {
                if (info.size > 0) {
                    val out = requireNotNull(dec.getOutputBuffer(index))
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    if (encoder == null) startEncoder(dec.outputFormat)
                    if (startUs < 0) startUs = info.presentationTimeUs.coerceAtLeast(0)
                    keep(out)
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                dec.releaseOutputBuffer(index, false)
                return true
            }
        }
        return false
    }

    private fun startEncoder(decoded: MediaFormat) {
        if (encoder != null) return
        val rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        inChannels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        floatPcm =
            decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        // After a seek the encoder must make what the init segment said at the start.
        if (sampleRate != 0 && rate != sampleRate) AppLog.w(TAG, "The sound changed from $sampleRate Hz to $rate Hz")
        sampleRate = rate
        outChannels = CastCodecs.channelsForTv(inChannels)
        val out =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, outChannels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, STEREO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, ENCODER_INPUT_BYTES)
            }
        AppLog.i(TAG) { "Sound: $inChannels channels at $sampleRate Hz, encoding $outChannels as AAC" }
        encoder =
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(out, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
    }

    /** Keeps decoded sound for the encoder as 16-bit, mixed down to stereo. */
    private fun keep(out: ByteBuffer) {
        val bytes = if (floatPcm) toPcm16(out) else ByteArray(out.remaining()).also { out.get(it) }
        val kept =
            if (outChannels == inChannels) {
                bytes
            } else {
                val samples = ShortArray(bytes.size / 2)
                val input = ByteBuffer.wrap(bytes)
                input.order(ByteOrder.nativeOrder())
                input.asShortBuffer().get(samples)
                val mixed = CastCodecs.downmix(samples, inChannels)
                val output = ByteBuffer.allocate(mixed.size * 2)
                output.order(ByteOrder.nativeOrder())
                output.asShortBuffer().put(mixed)
                output.array()
            }
        pcm.addLast(kept)
        pcmBytes += kept.size
    }

    /** Some decoders give 32-bit float samples; the AAC encoder takes 16-bit. */
    private fun toPcm16(out: ByteBuffer): ByteArray {
        val floats = out.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val pcm16 = ByteBuffer.allocate(floats.remaining() * 2).order(ByteOrder.nativeOrder())
        while (floats.hasRemaining()) {
            pcm16.putShort((floats.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        }
        return pcm16.array()
    }

    private fun feedEncoder(): Boolean {
        val enc = encoder ?: return false
        if (encoderInputDone) return false
        if (pcm.isEmpty() && !decoderDone) return false
        val index = enc.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        val buffer = requireNotNull(enc.getInputBuffer(index))
        buffer.clear()
        buffer.order(ByteOrder.nativeOrder())
        // Times are counted from the samples, so they always rise and match the sound exactly.
        val ptsUs = startUs.coerceAtLeast(0) + framesIn * US_PER_S / sampleRate
        var written = 0
        while (pcm.isNotEmpty() && buffer.remaining() > 0) {
            val chunk = pcm.removeFirst()
            val n = minOf(chunk.size, buffer.remaining())
            buffer.put(chunk, 0, n)
            written += n
            pcmBytes -= n
            if (n < chunk.size) pcm.addFirst(chunk.copyOfRange(n, chunk.size))
        }
        framesIn += written / (outChannels * 2)
        val end = pcm.isEmpty() && decoderDone
        enc.queueInputBuffer(index, 0, written, ptsUs, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
        if (end) encoderInputDone = true
        return true
    }

    private fun drainEncoder(): Boolean {
        val enc = encoder ?: return false
        val index = enc.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index < 0) return index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (!isConfig && info.size > 0) {
            val out = requireNotNull(enc.getOutputBuffer(index))
            out.position(info.offset)
            out.limit(info.offset + info.size)
            val data = ByteArray(info.size).also { out.get(it) }
            waiting.addLast(Fmp4.Sample(data, info.presentationTimeUs, key = true))
        }
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encodedDone = true
        enc.releaseOutputBuffer(index, false)
        return true
    }

    private fun releaseCodecs() {
        decoder?.let { d -> attempt(TAG, "Couldn't stop the sound decoder") { d.release() } }
        encoder?.let { e -> attempt(TAG, "Couldn't stop the sound encoder") { e.release() } }
        decoder = null
        encoder = null
    }

    override fun close() {
        releaseCodecs()
        attempt(TAG, "Couldn't close the sound reader") { ex.release() }
    }

    private companion object {
        const val TAG = "Cast"
        const val TIMEOUT_US = 2_000L
        const val MAX_PCM_BYTES = 1 shl 20
        const val STEREO_BITRATE = 192_000
        const val ENCODER_INPUT_BYTES = 64 * 1024
        const val US_PER_S = 1_000_000L
        const val MAX_SPINS = 20_000

        /** Read from a little before a piece, so the decoder is warmed up by its first frame. */
        const val PRE_ROLL_US = 200_000L
    }
}
