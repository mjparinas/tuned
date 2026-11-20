package com.tunedplayer.app.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Live HLS channels kept on the device (Melvin, 2026-11-20: "do 7"). They used to pass through untouched, so pause,
 * rewind and Record didn't work on `.m3u8` channels.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveBufferHlsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "live-hls-test")
    private val buffer = LiveBuffer(dir, keepMs = 60_000, maxBytes = 100_000_000)
    private val recorded = ByteArrayOutputStream()

    private val channel = "http://cdn.example/news/index.m3u8"
    private val variant = "http://cdn.example/news/hd/index.m3u8"

    /** What the "network" answers for each address. */
    private val answers =
        mutableMapOf(
            channel to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000\nhd/index.m3u8\n",
            variant to "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:4,\n7.ts\n#EXTINF:4,\n8.ts\n",
            "http://cdn.example/news/hd/7.ts" to "PIECE7",
            "http://cdn.example/news/hd/8.ts" to "PIECE8",
        )

    @After fun clean() {
        buffer.release()
    }

    private fun load(uri: String) {
        val tee = buffer.Tee(Fake(), beforeOpen = {})
        tee.open(DataSpec(Uri.parse(uri)))
        val chunk = ByteArray(1024)
        while (tee.read(chunk, 0, chunk.size) != C.RESULT_END_OF_INPUT) Unit
        tee.close()
    }

    private fun record() {
        buffer.startRecording(
            object : LiveBuffer.Recorder {
                override fun write(
                    data: ByteArray,
                    offset: Int,
                    length: Int,
                ) = recorded.write(data, offset, length)

                override fun end() = Unit
            },
        )
    }

    @Test
    fun theChannelsPiecesAreKeptAsThePlayerFetchesThem() {
        buffer.channel = Uri.parse(channel)
        load(channel)
        load(variant)
        assertTrue(buffer.isHls)
        assertTrue(buffer.keeping)
        assertEquals(variant, buffer.hlsMedia)
        record()
        load("http://cdn.example/news/hd/7.ts")
        load("http://cdn.example/news/hd/8.ts")
        // A retry of a piece already kept isn't kept twice.
        load("http://cdn.example/news/hd/8.ts")
        assertEquals("PIECE7PIECE8", recorded.toString())
        assertEquals(Uri.parse(channel), buffer.source)
    }

    @Test
    fun theNextPlaylistOfTheSameChannelKeepsWhatWasKept() {
        buffer.channel = Uri.parse(channel)
        load(channel)
        load(variant)
        record()
        load("http://cdn.example/news/hd/7.ts")
        // The player asks for the media playlist again as the window moves: the same channel, not a new one.
        load(variant)
        load("http://cdn.example/news/hd/8.ts")
        assertEquals("PIECE7PIECE8", recorded.toString())
    }

    @Test
    fun encryptedChannelsAreNotKept() {
        answers[variant] = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\n7.ts\n"
        buffer.channel = Uri.parse(channel)
        load(channel)
        load(variant)
        record()
        load("http://cdn.example/news/hd/7.ts")
        assertFalse(buffer.keeping)
        assertEquals("", recorded.toString())
    }

    @Test
    fun anotherChannelStartsAfresh() {
        buffer.channel = Uri.parse(channel)
        load(channel)
        load(variant)
        val other = "http://cdn.example/sports/index.m3u8"
        answers[other] = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\n1.ts\n"
        buffer.channel = Uri.parse(other)
        load(other)
        assertEquals(Uri.parse(other), buffer.source)
        assertEquals(other, buffer.hlsMedia)
    }

    /** Answers every address from [answers], as the network would. */
    private inner class Fake : DataSource {
        private var data = ByteArray(0)
        private var at = 0
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            data = answers[dataSpec.uri.toString()].orEmpty().toByteArray()
            at = 0
            return data.size.toLong()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (at >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - at)
            System.arraycopy(data, at, buffer, offset, n)
            at += n
            return n
        }

        override fun close() = Unit

        override fun getUri(): Uri? = uri

        override fun addTransferListener(transferListener: TransferListener) = Unit
    }
}
