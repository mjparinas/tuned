package app.tuned.player

import java.util.Locale
import kotlin.math.roundToInt

/** The plain-words parts of the player's pills and quality picker, kept free of Media3 so they can be tested on the JVM. */
object StreamInfo {
    private val codecs =
        mapOf(
            "video/avc" to "H.264",
            "video/hevc" to "H.265",
            "video/av01" to "AV1",
            "video/x-vnd.on2.vp9" to "VP9",
            "video/mpeg2" to "MPEG-2",
            "audio/mp4a-latm" to "AAC",
            "audio/ac3" to "AC-3",
            "audio/eac3" to "E-AC-3",
            "audio/eac3-joc" to "Dolby Atmos",
            "audio/ac4" to "AC-4",
            "audio/mpeg" to "MP3",
            "audio/mpeg-L2" to "MP2",
            "audio/opus" to "Opus",
            "audio/vnd.dts" to "DTS",
        )

    /** "H.264" for "video/avc"; an unknown type is shown as it is, and a missing one as nothing. */
    fun codec(mimeType: String?): String? = mimeType?.let { codecs[it] ?: it.substringAfter('/') }

    /** "6.2 Mbps" or "128 kbps"; nothing when unknown. */
    fun bitrate(bitsPerSecond: Int): String? = when {
        bitsPerSecond <= 0 -> null
        bitsPerSecond >= 1_000_000 -> "${formatNumber(bitsPerSecond / 1_000_000f)} Mbps"
        else -> "${bitsPerSecond / 1_000} kbps"
    }

    /** A quality picker label: "1080p · 6.2 Mbps", or the bitrate alone for audio-less variants with no height. */
    fun quality(
        height: Int,
        bitrate: Int,
    ): String = listOfNotNull(if (height > 0) "${height}p" else null, bitrate(bitrate)).joinToString(" · ")

    /**
     * Whether a stream's video renditions, as (height, bitrate), give a real choice: two or more that the picker
     * would label differently. A single TS rendition, or copies of one, is no choice.
     */
    fun hasQualityChoice(renditions: List<Pair<Int, Int>>): Boolean =
        renditions.map { (height, bitrate) -> quality(height, bitrate) }.distinct().size > 1

    /** How the sound is laid out, for the player's pill; [OTHER] keeps the channel count. */
    enum class Speakers { MONO, STEREO, SURROUND_5_1, SURROUND_7_1, OTHER }

    /** The pills over the player's title: picture size, frame rate, sound codec and speakers, each null when unknown. */
    data class Pills(
        val resolution: String?,
        val frameRate: Int?,
        val codec: String?,
        val speakers: Speakers?,
        val channels: Int,
    )

    fun pills(
        width: Int,
        height: Int,
        frameRate: Float,
        audioMimeType: String?,
        channels: Int,
    ): Pills = Pills(
        resolution = resolutionName(width, height),
        // 23.976 reads as 24 and 59.94 as 60, as players and TVs show them.
        frameRate = if (frameRate > 0) frameRate.roundToInt() else null,
        codec = codec(audioMimeType)?.uppercase(Locale.ROOT),
        speakers = speakers(channels),
        channels = channels,
    )

    /**
     * "1080p", "720p", "4K" and the like for a standard picture size, else the size itself ("1904x800"); null when
     * unknown (Melvin, 2026-11-19: "use the user friendly names. use resolution if there isn't one"). The height
     * decides first; a wide film cropped to fit (1920x800) is named by its width.
     */
    fun resolutionName(
        width: Int,
        height: Int,
    ): String? {
        if (width <= 0 || height <= 0) return null
        val byHeight = standardSizes.firstOrNull { it.height == height }
        val byWidth = standardSizes.firstOrNull { it.width == width && it.width >= WIDE_MATCH_MIN }
        return (byHeight ?: byWidth)?.name ?: "${width}x$height"
    }

    private class StandardSize(
        val name: String,
        val width: Int,
        val height: Int,
    )

    private val standardSizes =
        listOf(
            StandardSize("8K", 7680, 4320),
            StandardSize("4K", 3840, 2160),
            StandardSize("4K", 4096, 2160),
            StandardSize("1440p", 2560, 1440),
            StandardSize("1080p", 1920, 1080),
            StandardSize("720p", 1280, 720),
            StandardSize("576p", 720, 576),
            StandardSize("480p", 720, 480),
            StandardSize("360p", 640, 360),
        )

    /** Narrower widths are shared by several standard sizes (720 for 576p and 480p), so only these name a size alone. */
    private const val WIDE_MATCH_MIN = 1280

    fun speakers(channels: Int): Speakers? = when {
        channels <= 0 -> null
        channels == 1 -> Speakers.MONO
        channels == 2 -> Speakers.STEREO
        channels == SURROUND_5_1_CHANNELS -> Speakers.SURROUND_5_1
        channels == SURROUND_7_1_CHANNELS -> Speakers.SURROUND_7_1
        else -> Speakers.OTHER
    }

    private const val SURROUND_5_1_CHANNELS = 6
    private const val SURROUND_7_1_CHANNELS = 8

    private fun formatNumber(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)
}
