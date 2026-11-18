package app.tuned.core

import kotlin.math.roundToInt

/**
 * Whether a saved film or episode can go to a Chromecast as it is (Melvin, 2026-11-18: a saved MKV episode loaded on
 * an Nvidia Shield and stopped at once with a decode error, 102). A Chromecast's player is a web page: it plays H.264
 * and HEVC video and AAC, MP3, Opus, Vorbis and FLAC sound, but not the Dolby (AC-3, E-AC-3) or DTS sound most TV
 * rips in MKV carry, and MKV itself is not one of its formats. So a saved file is copied into an MP4 on the phone
 * first, the video as it is and the sound turned into AAC when it isn't already, which takes seconds rather than
 * re-encoding the picture.
 */
object CastCodecs {
    enum class Plan {
        /** Send the file as it is. */
        AS_IS,

        /** Copy both tracks into an MP4 unchanged. */
        REMUX,

        /** Copy the video into an MP4 and turn the sound into AAC. */
        REMUX_NEW_AUDIO,
    }

    /**
     * Whether the copy made for an earlier cast (modified at [copyModified], null when there is none) is still good for
     * a saved file modified at [sourceModified]. Copies are kept across casts and player visits (2026-11-18: closing
     * the player used to delete it, so every cast of the same episode copied it again).
     */
    fun reuseCopy(
        copyModified: Long?,
        sourceModified: Long,
    ): Boolean = copyModified != null && copyModified >= sourceModified

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

    /** Sound an MP4 can hold and every Chromecast plays. */
    private val audioForMp4 = setOf("audio/mp4a-latm", "audio/mpeg")

    /**
     * What to do with [fileName] holding a [video] track and an [audio] track (MIME types as Android names them, null
     * for none) of [channels] channels. Video that can't go in an MP4 is sent as it is, since only the picture would
     * need re-encoding, which a phone can't do quickly enough.
     *
     * Surround sound is turned into stereo even when it is already AAC (Melvin, 2026-11-18: on an Nvidia Shield every
     * film and episode stopped at once with error 102, from the provider and from the phone alike, while live channels,
     * which are mostly stereo, played).
     */
    fun plan(
        fileName: String,
        video: String?,
        audio: String?,
        channels: Int? = null,
    ): Plan {
        if (video !in VIDEO_FOR_MP4) return Plan.AS_IS
        val mp4 = fileName.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v")
        return when {
            audio != null && audio !in audioForMp4 -> Plan.REMUX_NEW_AUDIO
            audio != null && (channels ?: 0) > STEREO -> Plan.REMUX_NEW_AUDIO
            mp4 -> Plan.AS_IS
            else -> Plan.REMUX
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
    private const val SURROUND_51 = 6
    private const val FRONT_LEFT = 0
    private const val FRONT_RIGHT = 1
    private const val CENTRE = 2
    private const val LOW_FREQUENCY = 3
    private const val MINUS_3_DB = 0.7071
}
