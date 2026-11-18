package app.tuned.core

import kotlin.math.roundToInt

/**
 * What a Chromecast is given for a saved film or episode (Melvin, 2026-11-18: a saved MKV episode loaded on an Nvidia
 * Shield and stopped at once with a decode error, 102). A Chromecast's player is a web page: it plays H.264 and HEVC
 * video and AAC sound, but not the Dolby (AC-3, E-AC-3) or DTS sound most TV rips in MKV carry, and MKV itself is not
 * one of its formats. So the phone streams the file to it as HLS made on the fly ([CastHls]): the video as it is, the
 * sound as stereo AAC.
 */
object CastCodecs {
    /** A Cast receiver's detailed error code in words, for the log. */
    fun describeError(code: Int?): String = when (code) {
        100 -> "media unknown"
        101 -> "media aborted"
        102 -> "the TV couldn't decode the picture or sound"
        103 -> "network"
        104 -> "source not supported"
        110 -> "source buffer failure"
        311 -> "manifest load failed"
        else -> "code $code"
    }

    /** Video an MP4 can hold and a Chromecast plays. */
    val VIDEO_FOR_MP4 = setOf("video/avc", "video/hevc")

    /**
     * Whether a saved file with [video] (its MIME type as Android names it, null for none) can be streamed to a
     * Chromecast piece by piece. Other video goes as it is, since only the picture would need re-encoding, which a
     * phone can't do quickly enough.
     */
    fun streams(video: String?): Boolean = video in VIDEO_FOR_MP4

    /**
     * Whether [audio] of [channels] channels goes to the TV as it is. Anything but stereo or mono AAC is turned into
     * stereo AAC, surround included (Melvin, 2026-11-18: on his Shield every film and episode stopped at once with
     * error 102, from the provider and from the phone alike, while live channels, which are mostly stereo, played).
     */
    fun copiesSound(
        audio: String?,
        channels: Int?,
    ): Boolean = audio == AAC && channels != null && channels in 1..STEREO

    /**
     * The AudioSpecificConfig of AAC LC at [sampleRate] with [channels], as the init segment declares the sound the
     * phone's encoder makes.
     */
    fun aacConfig(
        sampleRate: Int,
        channels: Int,
    ): ByteArray {
        val index = AAC_RATES.indexOf(sampleRate)
        return if (index >= 0) {
            val v = (AAC_LC shl 11) or (index shl 7) or (channels shl 3)
            byteArrayOf((v ushr 8).toByte(), v.toByte())
        } else {
            // An unusual rate is written out in full after the escape index 15.
            val v = (AAC_LC.toLong() shl 35) or (15L shl 31) or (sampleRate.toLong() shl 7) or (channels.toLong() shl 3)
            ByteArray(5) { (v ushr (8 * (4 - it))).toByte() }
        }
    }

    /** How many channels the TV's sound gets: mono stays mono, anything more becomes stereo. */
    fun channelsForTv(channels: Int): Int = channels.coerceIn(1, STEREO)

    /**
     * Mixes interleaved 16-bit [pcm] of [channels] channels down to stereo, in Android's channel order (front left,
     * front right, centre, low frequency, then left and right surrounds and backs). The centre and surrounds go in at
     * -3 dB and the low frequency channel is left out, as Android's own mixer does for two speakers; scaling everything
     * down so peaks never clip would make speech too quiet, so the rare peak is clamped instead.
     */
    fun downmix(
        pcm: ShortArray,
        channels: Int,
    ): ShortArray {
        if (channels <= STEREO) return pcm
        val frames = pcm.size / channels
        val out = ShortArray(frames * STEREO)
        // How much of each channel goes to the left and the right.
        val left = DoubleArray(channels) { sides(it, channels).first }
        val right = DoubleArray(channels) { sides(it, channels).second }
        for (f in 0 until frames) {
            var l = 0.0
            var r = 0.0
            for (c in 0 until channels) {
                val v = pcm[f * channels + c].toDouble()
                l += v * left[c]
                r += v * right[c]
            }
            out[f * STEREO] = sample(l)
            out[f * STEREO + 1] = sample(r)
        }
        return out
    }

    /** The share of channel [c] (of [channels]) in the left and right speakers. */
    private fun sides(
        c: Int,
        channels: Int,
    ): Pair<Double, Double> = when {
        c == FRONT_LEFT -> 1.0 to 0.0
        c == FRONT_RIGHT -> 0.0 to 1.0
        c == CENTRE -> MINUS_3_DB to MINUS_3_DB
        c == LOW_FREQUENCY && channels >= SURROUND_51 -> 0.0 to 0.0
        c % 2 == 0 -> MINUS_3_DB to 0.0
        else -> 0.0 to MINUS_3_DB
    }

    private fun sample(v: Double): Short = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private const val STEREO = 2
    private const val AAC = "audio/mp4a-latm"
    private const val AAC_LC = 2
    private val AAC_RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    private const val SURROUND_51 = 6
    private const val FRONT_LEFT = 0
    private const val FRONT_RIGHT = 1
    private const val CENTRE = 2
    private const val LOW_FREQUENCY = 3
    private const val MINUS_3_DB = 0.7071
}
