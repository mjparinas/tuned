package app.tuned.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import app.tuned.data.AppSettings

/**
 * The parts of the player that come straight from Settings: buffering, track choices and sound. Kept out of
 * [PlayerActivity] so it only puts them together.
 */
@OptIn(UnstableApi::class)
internal object PlayerSetup {
    /** TS streams from IPTV servers often start on a non-IDR frame; these flags start them sooner. */
    fun extractors(): DefaultExtractorsFactory = DefaultExtractorsFactory().setTsExtractorFlags(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
    )

    fun loadControl(settings: AppSettings): LoadControl {
        val buffer = settings.buffer
        return DefaultLoadControl
            .Builder()
            .setBufferDurationsMs(buffer.minMs, buffer.maxMs, buffer.startMs, buffer.startMs * 2)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    fun trackSelector(
        context: Context,
        settings: AppSettings,
    ): DefaultTrackSelector {
        val captions = settings.captions
        return DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(settings.audioLanguage.ifBlank { null })
                    .setPreferredTextLanguage(captions.language.ifBlank { null })
                    .setSelectUndeterminedTextLanguage(true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captions.enabled)
                    // Android TV: the TV's video hardware keeps picture and sound together. Media3 uses it only
                    // when the TV's decoders for both the picture and the sound support it, and plays as usual otherwise.
                    .setTunnelingEnabled(settings.tunneledPlayback),
            )
        }
    }

    val audio: AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
}
