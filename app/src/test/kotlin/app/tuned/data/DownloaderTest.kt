package app.tuned.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloaderTest {
    @get:Rule val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val ranges = CopyOnWriteArrayList<String?>()
    private val file = ByteArray(300_000) { (it % 251).toByte() }
    private val downloader = Downloader(OkHttpClient(), backoffMs = { 0 })

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    /** Serves [file]; the first [drops] answers cut the connection partway through the body. */
    private fun serve(
        drops: Int,
        validator: Boolean,
    ) {
        var calls = 0
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    calls++
                    val range = request.headers["Range"]
                    ranges += range
                    val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                    val body = okio.Buffer().write(file, from, file.size - from)
                    return MockResponse
                        .Builder()
                        .code(if (range != null) 206 else 200)
                        .apply { if (validator) addHeader("ETag", "\"v1\"") }
                        .apply { if (range != null) addHeader("Content-Range", "bytes $from-${file.size - 1}/${file.size}") }
                        .body(body)
                        .apply { if (calls <= drops) onResponseBody(SocketEffect.CloseSocket()) }
                        .build()
                }
            }
    }

    @Test
    fun downloadsAndReportsProgress() = runTest {
        serve(drops = 0, validator = false)
        var last = 0L to 0L
        val out =
            downloader.download(server.url("/get.php").toString(), folder.root.resolve("playlist")) { bytes, total ->
                last =
                    bytes to total
            }
        assertContentEquals(file, out.readBytes())
        assertEquals(file.size.toLong() to file.size.toLong(), last)
        assertTrue(!folder.root.resolve("playlist.part").exists())
    }

    @Test
    fun resumesWhereItStoppedWhenTheServerAllowsIt() = runTest {
        serve(drops = 2, validator = true)
        val out = downloader.download(server.url("/get.php").toString(), folder.root.resolve("playlist"))
        assertContentEquals(file, out.readBytes())
        assertEquals(3, ranges.size)
        assertNull(ranges[0])
        assertTrue(ranges.drop(1).all { it != null && it != "bytes=0-" }, "later attempts resume: $ranges")
    }

    @Test
    fun startsOverWhenTheFileCouldHaveChanged() = runTest {
        serve(drops = 1, validator = false)
        val out = downloader.download(server.url("/get.php").toString(), folder.root.resolve("playlist"))
        assertContentEquals(file, out.readBytes())
        assertEquals(listOf<String?>(null, null), ranges.toList())
    }

    @Test
    fun aRefusedLoginIsNotRetried() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())
        val error =
            assertFailsWith<Downloader.HttpError> {
                downloader.download(server.url("/get.php").toString(), folder.root.resolve("playlist"))
            }
        assertEquals(401, error.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun givesUpAfterRepeatedFailuresWithoutProgress() = runTest {
        repeat(10) { server.enqueue(MockResponse.Builder().code(503).build()) }
        val few = Downloader(OkHttpClient(), maxStalls = 3, backoffMs = { 0 })
        assertFailsWith<IOException> { few.download(server.url("/get.php").toString(), folder.root.resolve("playlist")) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aSavedDownloadCarriesOnAfterBeingStopped() = runTest {
        // The first run is cut off (the player opening stops downloads); the next run picks up from the same bytes.
        serve(drops = 1, validator = true)
        val target = folder.root.resolve("film.mkv")
        val once = Downloader(OkHttpClient(), maxStalls = 1, backoffMs = { 0 })
        assertFailsWith<IOException> { once.download(server.url("/film.mkv").toString(), target, keepPart = true) }
        assertTrue(folder.root.resolve("film.mkv.part").length() > 0)
        val out = downloader.download(server.url("/film.mkv").toString(), target, keepPart = true)
        assertContentEquals(file, out.readBytes())
        assertEquals(2, ranges.size)
        assertNull(ranges[0])
        assertTrue(ranges[1] != null && ranges[1] != "bytes=0-", "the second run resumes: $ranges")
        assertTrue(!folder.root.resolve("film.mkv.part.tag").exists())
    }
}
