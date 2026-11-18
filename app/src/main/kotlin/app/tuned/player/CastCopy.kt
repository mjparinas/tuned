package app.tuned.player

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.StatFs
import app.tuned.core.CastCodecs
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns a saved film or episode into one a Chromecast plays ([CastCodecs]): an MP4 with the video copied as it is and
 * the sound copied or turned into AAC. Only one copy is kept, in the app's cache, and it is reused while the saved file
 * is unchanged. Subtitles and extra sound tracks are left out; the TV gets the first sound track.
 */
internal class CastCopy(
    private val dir: File,
) {
    /** The file to send for [source]: [source] itself when it plays as it is or can't be changed, else its copy. */
    suspend fun forCast(
        source: File,
        progress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val tracks = attempt(TAG, "Couldn't read the tracks of ${source.name}") { tracks(source) }.getOrNull() ?: return@withContext source
        val plan = CastCodecs.plan(source.name, tracks.video, tracks.audio)
        AppLog.i(TAG) { "${source.name}: video ${tracks.video}, sound ${tracks.audio} (${tracks.count} tracks), so $plan" }
        if (plan == CastCodecs.Plan.AS_IS) return@withContext source
        val target = File(dir, source.nameWithoutExtension + ".mp4")
        if (target.isFile && target.lastModified() >= source.lastModified()) {
            AppLog.i(TAG) { "Using the copy made before" }
            return@withContext target
        }
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // one copy at a time: they are as big as the film
        val free = attempt(TAG, "Couldn't read free space") { StatFs(dir.path).availableBytes }.getOrNull() ?: Long.MAX_VALUE
        if (free < source.length() + SPARE_BYTES) {
            AppLog.w(TAG, "Not enough space to copy ${source.name} for the TV (${free / MB} MB free), so it goes as it is")
            return@withContext source
        }
        val part = File(dir, target.name + ".part")
        val started = System.currentTimeMillis()
        val made =
            try {
                Remux(source, part, plan == CastCodecs.Plan.REMUX_NEW_AUDIO, progress).run()
                part.renameTo(target)
            } catch (e: IllegalStateException) {
                AppLog.w(TAG, "Couldn't copy ${source.name} for the TV", e)
                false
            } catch (e: IllegalArgumentException) {
                AppLog.w(TAG, "Couldn't copy ${source.name} for the TV", e)
                false
            } catch (e: java.io.IOException) {
                AppLog.w(TAG, "Couldn't copy ${source.name} for the TV", e)
                false
            } finally {
                part.delete()
            }
        if (!made) return@withContext source
        AppLog.i(TAG) { "Copied for the TV in ${(System.currentTimeMillis() - started) / MS_PER_S} s: ${target.length() / MB} MB" }
        target
    }

    /** Drops the copy, when a cast ends. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private data class Tracks(
        val video: String?,
        val audio: String?,
        val count: Int,
    )

    private fun tracks(source: File): Tracks {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(source.path)
            val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            return Tracks(mimes.firstOrNull { it.startsWith("video/") }, mimes.firstOrNull { it.startsWith("audio/") }, mimes.size)
        } finally {
            ex.release()
        }
    }

    /**
     * One copy, written in time order so the TV can start playing before it has read the whole file: video samples
     * copied straight across, sound samples copied or decoded and encoded again as AAC.
     */
    private class Remux(
        private val source: File,
        private val target: File,
        private val newAudio: Boolean,
        private val progress: (Int) -> Unit,
    ) {
        private val video = MediaExtractor()
        private val audio = MediaExtractor()
        private val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var transcoder: AacTranscoder? = null

        suspend fun run() {
            try {
                copy()
            } finally {
                transcoder?.release()
                video.release()
                audio.release()
                attempt(TAG, "Couldn't close the copy") { muxer.release() }
            }
        }

        private suspend fun copy() {
            video.setDataSource(source.path)
            audio.setDataSource(source.path)
            val videoIndex = (0 until video.trackCount).first { mime(video.getTrackFormat(it)).startsWith("video/") }
            val audioIndex = (0 until audio.trackCount).firstOrNull { mime(audio.getTrackFormat(it)).startsWith("audio/") }
            video.selectTrack(videoIndex)
            val videoFormat = video.getTrackFormat(videoIndex)
            val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) videoFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val videoTrack = muxer.addTrack(videoFormat)
            var audioTrack = -1
            if (audioIndex != null) {
                audio.selectTrack(audioIndex)
                val audioFormat = audio.getTrackFormat(audioIndex)
                if (newAudio) {
                    val t = AacTranscoder(audio, audioFormat).also { transcoder = it }
                    audioTrack = muxer.addTrack(t.outputFormat())
                } else {
                    audioTrack = muxer.addTrack(audioFormat)
                }
            }
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(maxSample(videoFormat))
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = audioIndex == null
            var lastPercent = -1
            while (!videoDone || !audioDone) {
                currentCoroutineContext().ensureActive()
                val videoTime = if (videoDone) Long.MAX_VALUE else video.sampleTime
                val audioTime = if (audioDone) Long.MAX_VALUE else (transcoder?.nextTimeUs ?: audio.sampleTime)
                if (!videoDone && videoTime <= audioTime) {
                    videoDone = !copySample(video, buffer, info, videoTrack)
                    if (durationUs > 0 && !videoDone) {
                        val percent = (videoTime * PERCENT / durationUs).toInt().coerceIn(0, PERCENT)
                        if (percent != lastPercent) progress(percent).also { lastPercent = percent }
                    }
                } else {
                    val t = transcoder
                    audioDone = if (t != null) !t.writeNext(muxer, audioTrack) else !copySample(audio, buffer, info, audioTrack)
                }
            }
            muxer.stop()
        }

        /** Copies one sample across; false at the end of the track. */
        private fun copySample(
            ex: MediaExtractor,
            buffer: ByteBuffer,
            info: MediaCodec.BufferInfo,
            track: Int,
        ): Boolean {
            buffer.clear()
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) return false
            val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, ex.sampleTime.coerceAtLeast(0), flags)
            muxer.writeSampleData(track, buffer, info)
            ex.advance()
            return true
        }

        private fun maxSample(format: MediaFormat): Int {
            val said = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            return maxOf(said, MIN_SAMPLE_BYTES)
        }
    }

    /**
     * Decodes the sound and encodes it again as AAC (up to 5.1; extra channels are left out), one encoded sample at a
     * time so it can be written between video samples. Timestamps are counted from the samples, so they always rise.
     */
    private class AacTranscoder(
        private val ex: MediaExtractor,
        input: MediaFormat,
    ) {
        private val decoder = MediaCodec.createDecoderByType(mime(input)).apply { configure(input, null, null, 0) }
        private var encoder: MediaCodec? = null
        private var format: MediaFormat? = null
        private val info = MediaCodec.BufferInfo()
        private val pcm = ArrayDeque<ByteArray>()
        private var pcmBytes = 0
        private var floatPcm = false
        private var inChannels = 2
        private var outChannels = 2
        private var sampleRate = DEFAULT_RATE
        private var framesIn = 0L
        private var startUs = -1L
        private var extractorDone = false
        private var decoderDone = false
        private var encoderInputDone = false
        private var encodedDone = false
        private var pending: Pair<ByteBuffer, MediaCodec.BufferInfo>? = null

        init {
            decoder.start()
        }

        /** Time of the next encoded sample, for writing in order. */
        val nextTimeUs: Long get() = pending?.second?.presentationTimeUs ?: lastTimeUs
        private var lastTimeUs = 0L

        /** Runs the decoder and encoder until the encoder says what it makes; needed before the MP4 can start. */
        fun outputFormat(): MediaFormat {
            var spins = 0
            while (format == null) {
                step(muxer = null, track = -1)
                check(++spins < MAX_SPINS) { "The sound encoder never started" }
            }
            return requireNotNull(format)
        }

        /** Writes the next encoded sample; false once the sound has ended. */
        fun writeNext(
            muxer: MediaMuxer,
            track: Int,
        ): Boolean {
            var spins = 0
            while (!encodedDone) {
                if (step(muxer, track)) return true
                check(++spins < MAX_SPINS) { "The sound stopped coming" }
            }
            return false
        }

        /** One turn of the pipeline; true when it wrote a sample. */
        private fun step(
            muxer: MediaMuxer?,
            track: Int,
        ): Boolean {
            feedDecoder()
            drainDecoder()
            feedEncoder()
            return drainEncoder(muxer, track)
        }

        private fun feedDecoder() {
            if (extractorDone || pcmBytes > MAX_PCM_BYTES) return
            val index = decoder.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return
            val buffer = requireNotNull(decoder.getInputBuffer(index))
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) {
                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                extractorDone = true
            } else {
                if (startUs < 0) startUs = ex.sampleTime.coerceAtLeast(0)
                decoder.queueInputBuffer(index, 0, size, ex.sampleTime.coerceAtLeast(0), 0)
                ex.advance()
            }
        }

        private fun drainDecoder() {
            if (decoderDone) return
            val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    startEncoder(decoder.outputFormat)
                }

                index >= 0 -> {
                    if (info.size > 0) {
                        val out = requireNotNull(decoder.getOutputBuffer(index))
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        if (encoder == null) startEncoder(decoder.outputFormat)
                        keep(out)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                    decoder.releaseOutputBuffer(index, false)
                }
            }
        }

        private fun startEncoder(decoded: MediaFormat) {
            if (encoder != null) return
            sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            inChannels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            floatPcm =
                decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            outChannels = inChannels.coerceIn(1, MAX_CHANNELS)
            val format =
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, outChannels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (outChannels > 2) SURROUND_BITRATE else STEREO_BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, ENCODER_INPUT_BYTES)
                }
            AppLog.i(TAG) { "Sound: $inChannels channels at $sampleRate Hz, encoding $outChannels as AAC" }
            encoder =
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
        }

        /** Keeps decoded sound for the encoder as 16-bit, leaving out channels past 5.1. */
        private fun keep(out: ByteBuffer) {
            val bytes = if (floatPcm) toPcm16(out) else ByteArray(out.remaining()).also { out.get(it) }
            val kept =
                if (outChannels == inChannels) {
                    bytes
                } else {
                    val frame = inChannels * 2
                    val keptFrame = outChannels * 2
                    val frames = bytes.size / frame
                    ByteArray(frames * keptFrame).also { k ->
                        for (f in 0 until frames) System.arraycopy(bytes, f * frame, k, f * keptFrame, keptFrame)
                    }
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

        private fun feedEncoder() {
            val enc = encoder ?: return
            if (encoderInputDone) return
            if (pcm.isEmpty() && !decoderDone) return
            val index = enc.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return
            val buffer = requireNotNull(enc.getInputBuffer(index))
            buffer.clear()
            buffer.order(ByteOrder.nativeOrder())
            val ptsUs = startUs.coerceAtLeast(0) + framesIn * US_PER_S / sampleRate
            var written = 0
            while (pcm.isNotEmpty() && buffer.remaining() > 0) {
                val chunk = pcm.removeFirst()
                val n = minOf(chunk.size, buffer.remaining())
                buffer.put(chunk, 0, n)
                written += n
                pcmBytes -= n
                if (n < chunk.size) {
                    pcm.addFirst(chunk.copyOfRange(n, chunk.size))
                }
            }
            framesIn += written / (outChannels * 2)
            val end = pcm.isEmpty() && decoderDone
            enc.queueInputBuffer(index, 0, written, ptsUs, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            if (end) encoderInputDone = true
        }

        private fun drainEncoder(
            muxer: MediaMuxer?,
            track: Int,
        ): Boolean {
            val enc = encoder ?: return false
            pending?.let { (buf, i) ->
                if (muxer == null) return false
                muxer.writeSampleData(track, buf, i)
                lastTimeUs = i.presentationTimeUs
                pending = null
                return true
            }
            val index = enc.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    format = enc.outputFormat
                }

                index >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var wrote = false
                    if (!isConfig && info.size > 0) {
                        val out = requireNotNull(enc.getOutputBuffer(index))
                        val copy = ByteBuffer.allocateDirect(info.size)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        copy.put(out)
                        copy.flip()
                        val i = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                        if (muxer != null) {
                            muxer.writeSampleData(track, copy, i)
                            lastTimeUs = i.presentationTimeUs
                            wrote = true
                        } else {
                            pending = copy to i
                        }
                    }
                    enc.releaseOutputBuffer(index, false)
                    if (end) encodedDone = true
                    return wrote
                }
            }
            return false
        }

        fun release() {
            attempt(TAG, "Couldn't stop the sound decoder") { decoder.release() }
            encoder?.let { e -> attempt(TAG, "Couldn't stop the sound encoder") { e.release() } }
        }

        private companion object {
            const val TIMEOUT_US = 2_000L
            const val MAX_PCM_BYTES = 1 shl 20
            const val MAX_CHANNELS = 6
            const val DEFAULT_RATE = 48_000
            const val STEREO_BITRATE = 192_000
            const val SURROUND_BITRATE = 384_000
            const val ENCODER_INPUT_BYTES = 64 * 1024
            const val US_PER_S = 1_000_000L
            const val MAX_SPINS = 20_000
        }
    }

    private companion object {
        const val TAG = "Cast"
        const val MB = 1_048_576L
        const val SPARE_BYTES = 200 * MB
        const val MS_PER_S = 1_000
        const val PERCENT = 100
        const val MIN_SAMPLE_BYTES = 8 * 1_048_576

        fun mime(format: MediaFormat): String = format.getString(MediaFormat.KEY_MIME).orEmpty()
    }
}
