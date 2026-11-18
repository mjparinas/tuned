package app.tuned.core

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
     * for none). Video that can't go in an MP4 is sent as it is, since only the picture would need re-encoding, which
     * a phone can't do quickly enough.
     */
    fun plan(
        fileName: String,
        video: String?,
        audio: String?,
    ): Plan {
        if (video !in VIDEO_FOR_MP4) return Plan.AS_IS
        val mp4 = fileName.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v")
        return when {
            audio != null && audio !in audioForMp4 -> Plan.REMUX_NEW_AUDIO
            mp4 -> Plan.AS_IS
            else -> Plan.REMUX
        }
    }
}
