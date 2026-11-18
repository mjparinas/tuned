package app.tuned.baselineprofile

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A stand-in IPTV provider on the loopback, so the journeys sign in, list and play without a real login (none is
 * ever committed). It serves one playlist the size of a mid-sized provider's and the same short clip for every
 * stream. It runs inside the test app; the app under test reaches it at 127.0.0.1 like any plain-HTTP server.
 */
object FakeProvider {
    private const val PORT = 8765
    private const val CHANNELS = 1500
    private const val FILMS = 600
    private const val SHOWS = 40
    private const val SEASONS = 2
    private const val EPISODES = 8
    private val GROUPS = listOf("News", "Sports", "Kids", "Music", "Documentaries", "Entertainment", "Local", "Weather")
    private const val BASE = "http://127.0.0.1:$PORT"

    const val PLAYLIST_URL = "$BASE/playlist.m3u"

    /** The name the first channel is listed under, to wait for. */
    const val FIRST_CHANNEL = "Channel 0001"

    private var server: ServerSocket? = null

    @Synchronized
    fun start(clip: ByteArray) {
        if (server != null) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT))
        server = socket
        thread(isDaemon = true, name = "fake-provider") {
            while (!socket.isClosed) {
                val client =
                    try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                thread(isDaemon = true, name = "fake-provider-client") { client.use { serve(it, clip) } }
            }
        }
    }

    private fun serve(
        client: Socket,
        clip: ByteArray,
    ) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
            while (reader.readLine()?.isNotEmpty() == true) {
                // Headers aren't needed.
            }
            val (type, body) =
                when {
                    path.startsWith("/playlist") -> "audio/x-mpegurl" to playlist
                    else -> "video/mp2t" to clip
                }
            val out = client.getOutputStream()
            val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray())
            out.write(body)
            out.flush()
        } catch (_: IOException) {
            // The player hung up mid-clip, as it does when a channel changes.
        }
    }

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
