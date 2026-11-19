package app.tuned.player

import android.app.PictureInPictureParams
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.tuned.core.Stalker
import app.tuned.core.StreamLink
import app.tuned.data.db.ItemEntity
import java.io.File

/** The stream (or the saved file) to play; a link's extra headers are kept by host for the data source. */
internal fun ItemEntity.toMediaItem(
    file: File?,
    streamHeaders: MutableMap<String, Map<String, String>>,
): MediaItem {
    val link = StreamLink.split(url)
    if (file == null && link.headers.isNotEmpty()) {
        link.url
            .toUri()
            .host
            ?.let { streamHeaders[it] = link.headers }
    }
    // A portal's channel gets its real link only as it opens, so an HLS one is named HLS up front.
    val hls = file == null && Stalker.parse(link.url)?.cmd?.let(Stalker::isHls) == true
    return MediaItem
        .Builder()
        .setMediaId(id.toString())
        .setUri(file?.toUri() ?: link.url.toUri())
        .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
        .build()
}

/** Picture-in-picture: the video's shape, where it is on screen, and whether leaving the app shrinks it on its own. */
@OptIn(UnstableApi::class)
internal fun pipParams(
    player: Player?,
    playerView: PlayerView,
    autoEnter: Boolean,
): PictureInPictureParams {
    val size = player?.videoSize
    val ratio =
        if (size != null && size.width > 0 && size.height > 0) {
            Rational(size.width, size.height).coerceForPip()
        } else {
            Rational(16, 9)
        }
    val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
    // Where the video is on screen, so the shrink into the window animates from the picture, not the whole screen.
    val bounds = Rect()
    if (playerView.videoSurfaceView?.getGlobalVisibleRect(bounds) == true) builder.setSourceRectHint(bounds)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnter && player?.isPlaying == true)
        builder.setSeamlessResizeEnabled(true)
    }
    return builder.build()
}

/** Android refuses ratios outside 1:2.39 to 2.39:1. */
private fun Rational.coerceForPip(): Rational {
    val value = toFloat()
    return when {
        value > 2.39f -> Rational(239, 100)
        value < 1 / 2.39f -> Rational(100, 239)
        else -> this
    }
}
