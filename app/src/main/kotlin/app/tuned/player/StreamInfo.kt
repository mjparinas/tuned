package app.tuned.player

import java.util.Locale

/** The plain-words parts of the player's stream info panel, kept free of Media3 so they can be tested on the JVM. */
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

    /** "1920×1080 · 50 fps · H.264 · 6.2 Mbps", leaving out whatever the stream doesn't say. */
    fun video(
        width: Int,
        height: Int,
        frameRate: Float,
        mimeType: String?,
        bitrate: Int,
    ): String = listOfNotNull(
        if (width > 0 && height > 0) "$width×$height" else null,
        if (frameRate > 0) "${formatNumber(frameRate)} fps" else null,
        codec(mimeType),
        bitrate(bitrate),
    ).joinToString(" · ")

    /** "AAC · 2 channels · 48 kHz · en", with [channels] already in words. */
    fun audio(
        mimeType: String?,
        channels: String?,
        sampleRate: Int,
        language: String?,
    ): String = listOfNotNull(
        codec(mimeType),
        channels,
        if (sampleRate > 0) "${formatNumber(sampleRate / 1_000f)} kHz" else null,
        language?.takeUnless { it.isBlank() || it == "und" },
    ).joinToString(" · ")

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

    private fun formatNumber(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)
}
