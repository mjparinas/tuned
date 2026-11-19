package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TsSegmenterTest {
    private class Pieces : TsSegmenter.Sink {
        val done = mutableListOf<Pair<ByteArray, Double>>()
        var current = java.io.ByteArrayOutputStream()

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = current.write(bytes, offset, length)

        override fun cut(seconds: Double) {
            done += current.toByteArray() to seconds
            current = java.io.ByteArrayOutputStream()
        }
    }

    private fun header(
        pid: Int,
        start: Boolean,
        adaptation: Boolean,
    ): ByteArray {
        val p = ByteArray(TsSegmenter.PACKET) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = (if (adaptation) 0x30 else 0x10).toByte()
        return p
    }

    private fun pat(): ByteArray {
        val p = header(0, start = true, adaptation = false)
        p[4] = 0 // pointer
        // table id, section length, ts id, version, section numbers
        byteArrayOf(0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00).copyInto(p, 5)
        // programme 1 -> PMT PID 0x1000
        byteArrayOf(0x00, 0x01, 0xF0.toByte(), 0x00).copyInto(p, 13)
        return p
    }

    private fun pmt(): ByteArray = header(0x1000, start = true, adaptation = false)

    /** A video packet with a PCR at [seconds], marked as a key frame when [key]. */
    private fun video(
        seconds: Double,
        key: Boolean,
    ): ByteArray {
        val p = header(0x100, start = key, adaptation = true)
        p[4] = 7
        p[5] = ((if (key) 0x40 else 0) or 0x10).toByte()
        val base = (seconds * 90_000).toLong()
        p[6] = (base shr 25).toByte()
        p[7] = (base shr 17).toByte()
        p[8] = (base shr 9).toByte()
        p[9] = (base shr 1).toByte()
        p[10] = ((base and 1) shl 7).toByte()
        return p
    }

    /** 20 s of stream: tables, then a video packet every half second, a key frame every 2 s. */
    private fun stream(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(pat())
        out.write(pmt())
        for (i in 0..40) out.write(video(i * 0.5, key = i % 4 == 0))
        return out.toByteArray()
    }

    @Test
    fun cutsAtKeyFramesOnceThereIsEnough() {
        val sink = Pieces()
        TsSegmenter(4.0, sink).feed(stream())
        assertEquals(listOf(4.0, 4.0, 4.0, 4.0, 4.0), sink.done.map { it.second })
        for ((bytes, _) in sink.done) {
            assertEquals(0x47, bytes[0].toInt())
            // Every piece opens with the PAT, then the PMT.
            assertEquals(0, ((bytes[1].toInt() and 0x1F) shl 8) or (bytes[2].toInt() and 0xFF))
            assertEquals(0x1000, ((bytes[189].toInt() and 0x1F) shl 8) or (bytes[190].toInt() and 0xFF))
            assertEquals(0, bytes.size % TsSegmenter.PACKET)
        }
    }

    @Test
    fun theSameWhateverTheChunkSize() {
        val whole = Pieces()
        TsSegmenter(4.0, whole).feed(stream())
        val bits = Pieces()
        val seg = TsSegmenter(4.0, bits)
        val data = stream()
        var i = 0
        var step = 1
        while (i < data.size) {
            val n = minOf(step, data.size - i)
            seg.feed(data, i, n)
            i += n
            step = step % 500 + 37
        }
        assertEquals(whole.done.map { it.second }, bits.done.map { it.second })
        assertTrue(whole.done.zip(bits.done).all { (a, b) -> a.first.contentEquals(b.first) })
    }

    @Test
    fun skipsGarbageBeforeTheFirstPacket() {
        val sink = Pieces()
        TsSegmenter(4.0, sink).feed(byteArrayOf(1, 2, 3) + stream())
        assertEquals(5, sink.done.size)
    }

    @Test
    fun writesALivePlaylist() {
        val text =
            LocalHls.playlist(
                listOf(LocalHls.Piece(7, "7.ts", 4.0), LocalHls.Piece(8, "8.ts", 3.52, discontinuity = true)),
                discontinuitySequence = 2,
            )
        assertTrue("#EXT-X-TARGETDURATION:4\n" in text)
        assertTrue("#EXT-X-MEDIA-SEQUENCE:7\n" in text)
        assertTrue("#EXT-X-DISCONTINUITY-SEQUENCE:2\n" in text)
        assertTrue("#EXT-X-DISCONTINUITY\n#EXTINF:3.520,\n8.ts\n" in text)
        assertTrue("ENDLIST" !in text)
    }
}
