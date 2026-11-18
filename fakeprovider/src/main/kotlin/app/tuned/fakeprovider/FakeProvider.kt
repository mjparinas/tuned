package app.tuned.fakeprovider

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A stand-in IPTV provider on the loopback, so the device tests sign in, list and play without a real login (none is
 * ever committed). It serves one playlist the size of a mid-sized provider's and, for every stream, a 30 second
 * clip sent at about the speed it plays, as a live channel arrives. It counts the stream connections open at once,
 * so a test can hold the app to its one-connection rule. It runs inside the test app; the app under test reaches it
 * at 127.0.0.1 like any plain-HTTP server.
 */
object FakeProvider {
    private const val PORT = 8765
    private const val CHANNELS = 1500
    private const val FILMS = 600
    private const val SHOWS = 40
    private const val SEASONS = 2
    private const val EPISODES = 8
    private const val CLIP_SECONDS = 30
    private const val CHUNK = 8 * 1024
    private const val MS_PER_SECOND = 1000L
    private val GROUPS = listOf("News", "Sports", "Kids", "Music", "Documentaries", "Entertainment", "Local", "Weather")
    private const val BASE = "http://127.0.0.1:$PORT"

    const val PLAYLIST_URL = "$BASE/playlist.m3u"

    /** The name the first film is listed under. */
    const val FIRST_FILM = "Film 001"

    /** The name the first channel is listed under, to wait for. */
    const val FIRST_CHANNEL = "Channel 0001"

    private var server: ServerSocket? = null
    private val clip: ByteArray by lazy {
        requireNotNull(FakeProvider::class.java.getResourceAsStream("/clip.mpegts")).use { it.readBytes() }
    }

    private val open = AtomicInteger()
    private val most = AtomicInteger()

    /** Every stream path asked for, in order (`/live/demo/demo/1.ts`). */
    val streamsRequested: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** The most stream connections that were open at the same moment since [reset]. */
    val mostOpenAtOnce: Int get() = most.get()

    @Synchronized
    fun start() {
        if (server != null) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        // IPv4 on purpose: Android's getLoopbackAddress() is ::1, and the playlist's links say 127.0.0.1.
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
        server = socket
        thread(isDaemon = true, name = "fake-provider") {
            while (!socket.isClosed) {
                val client =
                    try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                thread(isDaemon = true, name = "fake-provider-client") { client.use(::serve) }
            }
        }
    }

    fun reset() {
        streamsRequested.clear()
        most.set(open.get())
    }

    private fun serve(client: Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
            while (reader.readLine()?.isNotEmpty() == true) {
                // Headers aren't needed.
            }
            val out = client.getOutputStream()
            if (path.startsWith("/playlist")) {
                out.write(head("audio/x-mpegurl", playlist.size))
                out.write(playlist)
            } else {
                stream(path, out, client)
            }
            out.flush()
        } catch (_: IOException) {
            // The player hung up mid-clip, as it does when a channel changes.
        }
    }

    private fun stream(
        path: String,
        out: OutputStream,
        client: Socket,
    ) {
        streamsRequested += path
        most.accumulateAndGet(open.incrementAndGet(), ::maxOf)
        // Counted closed the moment the app hangs up, not at the next chunk sent, so a stream that opens right after
        // another closes isn't counted as two at once.
        val closed = AtomicBoolean()
        val close = { if (closed.compareAndSet(false, true)) open.decrementAndGet() }
        thread(isDaemon = true, name = "fake-provider-hangup") {
            try {
                while (client.getInputStream().read() >= 0) {
                    // The app sends nothing more after its request; -1 is the hang-up.
                }
            } catch (_: IOException) {
                // Closed either way.
            }
            close()
        }
        try {
            out.write(head("video/mp2t", clip.size))
            // About as fast as it plays, so the connection stays open the way a live channel's does.
            val pause = CLIP_SECONDS * MS_PER_SECOND * CHUNK / clip.size
            var at = 0
            while (at < clip.size) {
                val n = minOf(CHUNK, clip.size - at)
                out.write(clip, at, n)
                at += n
                Thread.sleep(pause)
            }
        } finally {
            close()
        }
    }

    private fun head(
        type: String,
        length: Int,
    ) = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray()

    private val playlist: ByteArray by lazy {
        buildString {
            appendLine("#EXTM3U")
            for (i in 1..CHANNELS) {
                val name = "Channel %04d".format(i)
                val group = GROUPS[i % GROUPS.size]
                appendLine("#EXTINF:-1 tvg-id=\"ch$i\" tvg-name=\"$name\" group-title=\"$group\",$name")
                appendLine("$BASE/live/demo/demo/$i.ts")
            }
            for (i in 1..FILMS) {
                val name = "Film %03d".format(i)
                appendLine("#EXTINF:-1 tvg-name=\"$name\" group-title=\"Movies | ${GROUPS[i % GROUPS.size]}\",$name")
                appendLine("$BASE/movie/demo/demo/$i.mp4")
            }
            var id = 1
            for (show in 1..SHOWS) {
                for (season in 1..SEASONS) {
                    for (episode in 1..EPISODES) {
                        val name = "Show %02d S%02d E%02d".format(show, season, episode)
                        appendLine("#EXTINF:-1 tvg-name=\"$name\" group-title=\"Series | Drama\",$name")
                        appendLine("$BASE/series/demo/demo/${id++}.mp4")
                    }
                }
            }
        }.toByteArray()
    }
}
