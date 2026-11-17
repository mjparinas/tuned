package app.tuned.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads a large file (a 400 MB playlist on a slow server) to disk, surviving dropped connections.
 *
 * - Long timeouts: slow panels can take minutes to start answering, then stall for a while mid-file.
 * - On a dropped connection it tries again with backoff. It resumes from the bytes it already has (HTTP Range)
 *   when the server supports ranges and names a validator (ETag or Last-Modified), so a changed file is never
 *   stitched onto an old one; otherwise it starts over.
 * - Only consecutive attempts that make no progress count towards the limit, so a long download on a flaky line
 *   keeps going as long as it keeps moving.
 * - The body is saved as sent (gzip stays gzip); the reader unwraps it, see `Streams.maybeGunzip`.
 */
class Downloader(
    http: OkHttpClient,
    private val maxStalls: Int = MAX_STALLS,
    private val backoffMs: (
        attempt: Int,
    ) -> Long = { attempt -> (BASE_BACKOFF_MS shl (attempt - 1).coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS) },
) {
    private val http =
        http
            .newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()

    /** Bytes saved so far and the full size, or -1 when the server doesn't say. */
    fun interface Progress {
        fun onProgress(
            bytes: Long,
            total: Long,
        )
    }

    suspend fun download(
        url: String,
        target: File,
        userAgent: String = "",
        progress: Progress = Progress { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        part.delete()
        val state = State()
        var stalls = 0
        var lastError: IOException? = null
        while (stalls < maxStalls) {
            coroutineContext.ensureActive()
            val before = if (part.exists()) part.length() else 0L
            try {
                attempt(url, userAgent, part, state, progress)
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                return@withContext target
            } catch (e: HttpError) {
                // A server that says no (wrong login, missing file) won't change its mind on a retry.
                if (e.isFinal) {
                    part.delete()
                    throw e
                }
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
            val after = if (part.exists()) part.length() else 0L
            stalls = if (after > before) 1 else stalls + 1
            if (stalls < maxStalls) delay(backoffMs(stalls))
        }
        part.delete()
        throw lastError ?: IOException("Couldn't download")
    }

    private class State {
        var validator: String? = null
        var total = -1L
    }

    /** One connection: resumes [part] when it can, otherwise starts it over. Throws when the file isn't complete. */
    private suspend fun attempt(
        url: String,
        userAgent: String,
        part: File,
        state: State,
        progress: Progress,
    ) {
        val have = if (part.exists()) part.length() else 0L
        val validator = state.validator
        val resume = have > 0 && validator != null
        if (!resume) part.delete()
        val request =
            Request
                .Builder()
                .url(url)
                // Asking for gzip ourselves turns off OkHttp's transparent unzip, so the bytes on disk are the bytes
                // the server counts for Range.
                .header("Accept-Encoding", "gzip")
                .apply { if (userAgent.isNotBlank()) header("User-Agent", userAgent) }
                .apply { if (resume && validator != null) header("Range", "bytes=$have-").header("If-Range", validator) }
                .build()
        http.newCall(request).execute().use { response ->
            val complete = response.code == HTTP_RANGE_NOT_SATISFIABLE && resume && have == state.total
            if (!complete) {
                if (!response.isSuccessful) throw HttpError(response.code)
                val append = resume && response.code == HTTP_PARTIAL
                state.validator = response.validator()
                state.total =
                    if (append) {
                        response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                    } else {
                        response.body.contentLength()
                    }
                save(response, part, append, state.total, progress)
            }
        }
        if (state.total >= 0 && part.length() != state.total) throw IOException("The download ended early")
    }

    private suspend fun save(
        response: Response,
        part: File,
        append: Boolean,
        total: Long,
        progress: Progress,
    ) {
        var bytes = if (append) part.length() else 0L
        progress.onProgress(bytes, total)
        response.body.byteStream().use { input ->
            FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    bytes += n
                    progress.onProgress(bytes, total)
                }
            }
        }
    }

    private fun Response.validator(): String? = header("ETag")?.takeUnless { it.startsWith("W/") } ?: header("Last-Modified")

    class HttpError(
        val code: Int,
    ) : IOException("Server answered $code") {
        val isFinal get() = code in 400..499 && code != HTTP_TIMEOUT && code != HTTP_TOO_MANY
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 30L
        const val READ_TIMEOUT_S = 180L
        const val MAX_STALLS = 6
        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val BUFFER = 64 * 1024
        const val HTTP_PARTIAL = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val HTTP_TIMEOUT = 408
        const val HTTP_TOO_MANY = 429
    }
}
