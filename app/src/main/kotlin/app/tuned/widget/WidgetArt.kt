package app.tuned.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import app.tuned.diagnostics.attempt
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Logos and posters for the home screen widgets, as small software bitmaps (a widget is drawn by the launcher, which
 * can't take hardware bitmaps). They come through the app's own image loader, so a logo the lists already showed is read
 * from the device's cache, not downloaded again. Kept small, because every widget update hands each bitmap to the
 * launcher and Android caps the total.
 */
internal object WidgetArt {
    /** Loads every address in [urls] (in parallel), each no bigger than [width] by [height] pixels; null where it fails. */
    suspend fun load(
        context: Context,
        urls: List<String?>,
        width: Int,
        height: Int,
    ): List<Bitmap?> = coroutineScope {
        urls.map { url -> async { url?.let { one(context, it, width, height) } } }.awaitAll()
    }

    private suspend fun one(
        context: Context,
        url: String,
        width: Int,
        height: Int,
    ): Bitmap? {
        val request =
            ImageRequest
                .Builder(context)
                .data(url)
                .size(width, height)
                .allowHardware(false)
                .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        return attempt(TAG, "Couldn't draw a widget picture") { fit(result.image.toBitmap(), width, height) }.getOrNull()
    }

    /** A cached copy can be bigger than asked for; shrink it, keeping its shape. */
    private fun fit(
        bitmap: Bitmap,
        width: Int,
        height: Int,
    ): Bitmap {
        val ratio = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        if (ratio >= 1f) return bitmap
        return bitmap.scale((bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1))
    }

    private const val TAG = "Widgets"
}
