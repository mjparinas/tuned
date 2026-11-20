package com.tunedplayer.app.core

import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileServerTest {
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val file = File.createTempFile("episode", ".mp4").apply { writeBytes(bytes) }
    private val server = FileServer()

    @AfterTest
    fun cleanUp() {
        server.close()
        file.delete()
    }

    private fun open(
        path: String,
        method: String = "GET",
        range: String? = null,
    ): HttpURLConnection {
        val url = URI("http://127.0.0.1:${server.port}$path").toURL()
        return (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = method
            range?.let { setRequestProperty("Range", it) }
        }
    }

    @Test
    fun servesASavedFileWholeWithItsTypeAndCors() {
        val c = open(server.share(file))
        assertEquals(200, c.responseCode)
        assertEquals("video/mp4", c.contentType)
        assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"))
        assertEquals("bytes", c.getHeaderField("Accept-Ranges"))
        assertContentEquals(bytes, c.inputStream.use { it.readBytes() })
    }

    @Test
    fun servesTheRangeATvAsksForWhenItSeeks() {
        val c = open(server.share(file), range = "bytes=150000-")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 150000-199999/200000", c.getHeaderField("Content-Range"))
        assertContentEquals(bytes.copyOfRange(150_000, 200_000), c.inputStream.use { it.readBytes() })

        val head = open(server.share(file), method = "HEAD", range = "bytes=0-99")
        assertEquals(206, head.responseCode)
        assertEquals("100", head.getHeaderField("Content-Length"))
    }

    @Test
    fun servesNothingThatWasntShared() {
        val path = server.share(file)
        assertEquals(404, open("/" + file.name).responseCode)
        assertEquals(404, open(path.substringBeforeLast('/') + "/other.mp4").responseCode)
        assertEquals(416, open(path, range = "bytes=300000-").responseCode)
    }

    @Test
    fun servesFilesAStreamMakesWhenAskedFor() {
        val asked = ArrayList<String>()
        val stream =
            object : FileServer.Made {
                override fun bytes(file: String): ByteArray? {
                    asked += file
                    return if (file == "p3.m4s") byteArrayOf(1, 2, 3, 4) else null
                }

                override fun type(file: String) = "video/mp4"
            }
        val folder = server.share("42", stream)
        val piece = open(folder + "p3.m4s")
        assertEquals(200, piece.responseCode)
        assertEquals("video/mp4", piece.contentType)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), piece.inputStream.use { it.readBytes() })
        assertEquals(206, open(folder + "p3.m4s", range = "bytes=2-").responseCode)
        assertEquals(404, open(folder + "p9.m4s").responseCode)
        assertEquals(listOf("p3.m4s", "p3.m4s", "p9.m4s"), asked)
        // Once the cast moves on, nothing is served from it.
        server.unshare(folder)
        assertEquals(404, open(folder + "p3.m4s").responseCode)
    }

    @Test
    fun aStreamThatFailsAnswersWithAnErrorRatherThanStopping() {
        val folder =
            server.share(
                "7",
                object : FileServer.Made {
                    override fun bytes(file: String): ByteArray = error("decoder gone")

                    override fun type(file: String) = "video/mp4"
                },
            )
        assertEquals(500, open(folder + "p0.m4s").responseCode)
        assertEquals(200, open(server.share(file)).responseCode)
    }

    @Test
    fun readsTheRangeHeadersTvsSend() {
        assertNull(ByteRanges.parse(null, 1000))
        assertEquals(0L to 999L, ByteRanges.parse("bytes=0-", 1000))
        assertEquals(100L to 199L, ByteRanges.parse("bytes=100-199", 1000))
        assertEquals(500L to 999L, ByteRanges.parse("bytes=500-5000", 1000))
        assertEquals(900L to 999L, ByteRanges.parse("bytes=-100", 1000))
        assertEquals(ByteRanges.UNSATISFIABLE, ByteRanges.parse("bytes=1000-", 1000))
        assertNull(ByteRanges.parse("bytes=0-1,5-9", 1000))
        assertNull(ByteRanges.parse("items=0-1", 1000))
    }
}
