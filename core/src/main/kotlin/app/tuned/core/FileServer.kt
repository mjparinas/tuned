package app.tuned.core

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands a saved film or episode to a TV on the same Wi-Fi (Melvin, 2026-11-17: casting a downloaded episode did
 * nothing). A TV can't open a file on the phone, and sending it the provider's link would stream what is already saved
 * ("saved before streamed"), so the phone serves the file itself: plain HTTP with byte ranges, which is what a Chromecast
 * or DLNA TV asks for when it starts and seeks.
 *
 * Only files [share]d are served, each under a path with a random part, so another device on the network can't guess
 * one; files a stream makes as the TV asks for them ([Made]) are served the same way. One request per connection
 * keeps it simple; TVs open a new one for each range.
 */
class FileServer(
    private val log: (String) -> Unit = {},
) : Closeable {
    private val socket = ServerSocket(0)
    private val files = ConcurrentHashMap<String, File>()
    private val made = ConcurrentHashMap<String, Made>()
    private val key = randomKey()
    private val handlers = AtomicInteger()

    val port: Int get() = socket.localPort

    init {
        Thread(::accept, "FileServer").apply { isDaemon = true }.start()
    }

    /** Serves [file] and returns its path on this server, such as `/k3x…/42.mp4`. */
    fun share(file: File): String {
        val path = "/$key/" + URLEncoder.encode(file.name, Charsets.UTF_8).replace("+", "%20")
        files[path] = file
        return path
    }

    /** Files that are made when asked for, such as a stream's playlist and pieces ([CastHls]). */
    interface Made {
        /** The bytes of [file], or null when there is no such file. Called on the request's own thread. */
        fun bytes(file: String): ByteArray?

        fun type(file: String): String
    }

    /** Serves [source]'s files and returns the folder they are under on this server, such as `/k3x…/42/`. */
    fun share(
        name: String,
        source: Made,
    ): String {
        val folder = "/$key/" + URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20") + "/"
        made[folder] = source
        return folder
    }

    /** Stops serving what [share] returned [path] for. */
    fun unshare(path: String) {
        files.remove(path)
        made.remove(path)
    }

    override fun close() {
        files.clear()
        made.clear()
        try {
            socket.close()
        } catch (e: IOException) {
            log("Couldn't close: ${e.message}")
        }
    }

    private fun accept() {
        while (!socket.isClosed) {
            val client =
                try {
                    socket.accept()
                } catch (e: SocketException) {
                    log("Stopped: ${e.message}") // closed
                    return
                }
            if (handlers.incrementAndGet() > MAX_HANDLERS) {
                handlers.decrementAndGet()
                client.close()
                continue
            }
            Thread({ handle(client) }, "FileServer-client").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { serve(it) }
        } catch (e: IOException) {
            // A TV drops the connection whenever it seeks or has enough buffered; that is normal.
            log("Connection ended early: ${e.message}")
        } finally {
            handlers.decrementAndGet()
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = TIMEOUT_MS
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        val out = BufferedOutputStream(client.getOutputStream(), BUFFER)
        val file = files[path]?.takeIf { it.isFile }
        val folder = path.substringBeforeLast('/') + "/"
        val source = if (file == null) made[folder] else null
        when {
            method == "OPTIONS" -> {
                respond(out, "204 No Content", emptyMap())
            }

            method != "GET" && method != "HEAD" -> {
                respond(out, "405 Method Not Allowed", emptyMap())
            }

            source != null -> {
                sendMade(out, source, path.substringAfterLast('/'), method == "HEAD", headers["range"])
            }

            file == null -> {
                log("Asked for an unknown file ($method)")
                respond(out, "404 Not Found", emptyMap())
            }

            else -> {
                sendFile(out, file, method == "HEAD", headers["range"])
            }
        }
        out.flush()
    }

    private fun sendFile(
        out: OutputStream,
        file: File,
        headOnly: Boolean,
        rangeHeader: String?,
    ) {
        val length = file.length()
        val range = ByteRanges.parse(rangeHeader, length)
        val type = mapOf("Content-Type" to CastMedia.mimeFor(file.name))
        if (range == ByteRanges.UNSATISFIABLE) {
            log("Asked for ${rangeHeader.orEmpty()} of a $length-byte file")
            return respond(out, "416 Range Not Satisfiable", type + ("Content-Range" to "bytes */$length"))
        }
        val (start, end) = range ?: (0L to length - 1)
        val count = (end - start + 1).coerceAtLeast(0)
        val contentRange = if (range != null) mapOf("Content-Range" to "bytes $start-$end/$length") else emptyMap()
        val headers = type + ("Content-Length" to count.toString()) + contentRange
        log("${if (headOnly) "HEAD" else "GET"} ${file.name} bytes $start-$end of $length")
        respond(out, if (range != null) "206 Partial Content" else "200 OK", headers)
        if (headOnly || count == 0L) return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(BUFFER)
            var left = count
            while (left > 0) {
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                left -= read
            }
        }
    }

    private fun sendMade(
        out: OutputStream,
        source: Made,
        name: String,
        headOnly: Boolean,
        rangeHeader: String?,
    ) {
        val started = System.nanoTime()
        val bytes =
            try {
                source.bytes(name)
            } catch (e: IllegalStateException) {
                log("Couldn't make $name: $e")
                return respond(out, "500 Internal Server Error", emptyMap())
            } catch (e: IllegalArgumentException) {
                log("Couldn't make $name: $e")
                return respond(out, "500 Internal Server Error", emptyMap())
            }
        if (bytes == null) {
            log("Asked for $name, which isn't part of the stream")
            return respond(out, "404 Not Found", emptyMap())
        }
        val length = bytes.size.toLong()
        val type = mapOf("Content-Type" to source.type(name), "Cache-Control" to "no-cache")
        val range = ByteRanges.parse(rangeHeader, length)
        if (range == ByteRanges.UNSATISFIABLE) {
            return respond(out, "416 Range Not Satisfiable", type + ("Content-Range" to "bytes */$length"))
        }
        val (start, end) = range ?: (0L to length - 1)
        val count = (end - start + 1).coerceAtLeast(0)
        val contentRange = if (range != null) mapOf("Content-Range" to "bytes $start-$end/$length") else emptyMap()
        log("${if (headOnly) "HEAD" else "GET"} $name: $length bytes, made in ${(System.nanoTime() - started) / NS_PER_MS} ms")
        respond(out, if (range != null) "206 Partial Content" else "200 OK", type + ("Content-Length" to count.toString()) + contentRange)
        if (!headOnly && count > 0) out.write(bytes, start.toInt(), count.toInt())
    }

    private fun respond(
        out: OutputStream,
        status: String,
        headers: Map<String, String>,
    ) {
        val all =
            linkedMapOf(
                "Accept-Ranges" to "bytes",
                // A Chromecast's player is a web page, so it needs these to read from another address.
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Headers" to "Range, Content-Type",
                "Access-Control-Expose-Headers" to "Content-Length, Content-Range",
                // DLNA TVs only offer seeking when told the file allows it.
                "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0",
                "transferMode.dlna.org" to "Streaming",
                "Connection" to "close",
            )
        if (headers["Content-Length"] == null) all["Content-Length"] = "0"
        all.putAll(headers)
        val text =
            buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                all.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                append("\r\n")
            }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length >= MAX_LINE) return null
            line.append(b.toChar())
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val BUFFER = 64 * 1024
        const val MAX_LINE = 8 * 1024
        const val MAX_HANDLERS = 16
        const val KEY_BYTES = 12
        const val NS_PER_MS = 1_000_000L

        fun randomKey(): String {
            val bytes = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** HTTP `Range: bytes=…` for one range, the only kind TVs send. */
object ByteRanges {
    /** Marks a range that lies past the end of the file. */
    val UNSATISFIABLE = -1L to -1L

    /**
     * The first and last byte asked for, both inclusive; null for the whole file (no header, or one this doesn't read,
     * such as several ranges); [UNSATISFIABLE] when it starts past the end.
     */
    fun parse(
        header: String?,
        length: Long,
    ): Pair<Long, Long>? {
        val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(BYTES.length) ?: return null
        if (',' in spec) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val first = spec.substring(0, dash).trim()
        val last = spec.substring(dash + 1).trim()
        return when {
            first.isEmpty() -> {
                // The last n bytes.
                val n = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
                if (length == 0L) UNSATISFIABLE else (length - n).coerceAtLeast(0) to length - 1
            }

            else -> {
                val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: return null
                val end = if (last.isEmpty()) length - 1 else last.toLongOrNull()?.takeIf { it >= start } ?: return null
                if (start >= length) UNSATISFIABLE else start to minOf(end, length - 1)
            }
        }
    }

    private const val BYTES = "bytes="
}
