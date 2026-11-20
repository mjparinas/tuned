package com.tunedplayer.app.data

import com.tunedplayer.app.core.TsAlign
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scheduled recording's receiver (Melvin, 2026-11-20: "do 4, 6, 7, 8"): a TS stream from its first whole packet, an HLS
 * channel from the live edge in its best quality, and never a second request at the same time.
 */
class LiveCaptureTest {
    private val server = MockWebServer()
    private val open = AtomicInteger()
    private val most = AtomicInteger()
    private var polls = 0

    /** A clock that moves a second each time it's read, so a capture "until" a few seconds ends quickly. */
    private var clock = 0L
    private val capture =
        LiveCapture(OkHttpClient(), now = {
            clock += 1_000
            clock
        }, sleep = {})

    @Before fun start() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    most.accumulateAndGet(open.incrementAndGet()) { a, b -> maxOf(a, b) }
                    try {
                        return when (request.url.encodedPath) {
                            "/live.ts" -> {
                                bytes(byteArrayOf(1, 2, 3) + packets(10, 0))
                            }

                            "/master.m3u8" -> {
                                text(MASTER)
                            }

                            "/hi/index.m3u8" -> {
                                text(media(first = 10 + polls++))
                            }

                            "/encrypted.m3u8" -> {
                                text("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:6,\np1.ts\n")
                            }

                            else -> {
                                bytes(
                                    packets(
                                        2,
                                        request.url.encodedPath
                                            .filter(Char::isDigit)
                                            .toInt(),
                                    ),
                                )
                            }
                        }
                    } finally {
                        open.decrementAndGet()
                    }
                }
            }
        server.start()
    }

    @After fun stop() = server.close()

    @Test
    fun `a TS stream is saved from its first whole packet`() {
        val out = ByteArrayOutputStream()
        val end =
            capture.capture(
                server.url("/live.ts").toString(),
                "Tuned",
                until = 3_000,
                keepGoing = { true },
            ) { d, o, n -> out.write(d, o, n) }
        assertEquals(LiveCapture.End.DONE, end)
        val saved = out.toByteArray()
        assertTrue(saved.isNotEmpty())
        assertEquals(0x47.toByte(), saved[0], "starts on a packet, not the 3 stray bytes before it")
        assertEquals(0, saved.size % TsAlign.PACKET)
    }

    @Test
    fun `an HLS channel is recorded from the live edge in its best quality, one request at a time`() {
        val out = ByteArrayOutputStream()
        capture.capture(
            server.url("/master.m3u8").toString(),
            "Tuned",
            until = 30_000,
            keepGoing = { true },
        ) { d, o, n -> out.write(d, o, n) }
        val pieces = server.takeAll().filter { it.endsWith(".ts") }
        // The window's last piece first (12), then each new one once, never an older one again.
        assertEquals(pieces.distinct(), pieces)
        assertEquals("/hi/p12.ts", pieces.first())
        assertTrue(pieces.none { it.startsWith("/lo/") }, "the best quality only")
        assertEquals(1, most.get(), "one connection at a time")
        assertTrue(out.size() > 0)
    }

    @Test
    fun `encrypted HLS can't be saved as one file`() {
        val end = capture.capture(server.url("/encrypted.m3u8").toString(), "Tuned", until = 30_000, keepGoing = { true }) { _, _, _ -> }
        assertEquals(LiveCapture.End.UNSUPPORTED, end)
    }

    @Test
    fun `stopping hangs up`() {
        var chunks = 0
        val end =
            capture.capture(server.url("/live.ts").toString(), "Tuned", until = Long.MAX_VALUE, keepGoing = { chunks < 1 }) { _, _, _ ->
                chunks++
            }
        assertEquals(LiveCapture.End.STOPPED, end)
    }

    private fun MockWebServer.takeAll(): List<String> = buildList {
        while (true) add(takeRequest(0, TimeUnit.SECONDS)?.url?.encodedPath ?: break)
    }

    private fun media(first: Int): String = buildString {
        append("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$first\n")
        for (i in first until first + 3) append("#EXTINF:6,\np$i.ts\n")
    }

    private fun packets(
        count: Int,
        mark: Int,
    ): ByteArray = ByteArray(count * TsAlign.PACKET) { i -> if (i % TsAlign.PACKET == 0) 0x47 else mark.toByte() }

    private fun bytes(data: ByteArray) = MockResponse
        .Builder()
        .addHeader("Content-Type", "video/mp2t")
        .body(okio.Buffer().write(data))
        .build()

    private fun text(body: String) = MockResponse
        .Builder()
        .addHeader("Content-Type", "application/vnd.apple.mpegurl")
        .body(body)
        .build()

    private companion object {
        val MASTER =
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            lo/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4000000
            hi/index.m3u8
            """.trimIndent()
    }
}
