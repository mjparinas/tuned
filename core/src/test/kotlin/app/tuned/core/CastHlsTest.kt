package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastHlsTest {
    @Test
    fun piecesStartAtKeyFramesAboutSixSecondsApart() {
        val keys = (0..30 step 2).map { it * 1_000_000L } // a key frame every 2 s
        assertEquals(listOf(0L, 6_000_000L, 12_000_000L, 18_000_000L, 24_000_000L, 30_000_000L), CastHls.starts(keys))
        // Key frames far apart give longer pieces, never empty ones.
        assertEquals(listOf(0L, 10_000_000L, 20_000_000L), CastHls.starts(listOf(0L, 10_000_000L, 20_000_000L)))
        assertEquals(listOf(0L), CastHls.starts(emptyList()))
    }

    @Test
    fun thePlaylistListsEveryPieceAndEnds() {
        val text = CastHls.playlist(listOf(40_000L, 6_040_000L, 12_040_000L), durationUs = 15_000_000L)
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertTrue("#EXT-X-PLAYLIST-TYPE:VOD" in text)
        assertTrue("#EXT-X-MAP:URI=\"init.mp4\"" in text)
        assertTrue("#EXTINF:6.040,\np0.m4s\n#EXTINF:6.000,\np1.m4s\n#EXTINF:2.960,\np2.m4s\n" in text)
        assertTrue("#EXT-X-TARGETDURATION:7\n" in text) // rounded up, as HLS requires
        assertTrue(text.endsWith("#EXT-X-ENDLIST\n"))
    }

    @Test
    fun pieceNamesGoBothWays() {
        assertEquals("p12.m4s", CastHls.pieceName(12))
        assertEquals(12, CastHls.pieceIndex("p12.m4s"))
        assertNull(CastHls.pieceIndex("index.m3u8"))
        assertNull(CastHls.pieceIndex("p-1.m4s"))
        assertNull(CastHls.pieceIndex("px.m4s"))
        assertEquals("application/vnd.apple.mpegurl", CastHls.mimeFor(CastHls.PLAYLIST))
        assertEquals("video/mp4", CastHls.mimeFor("p0.m4s"))
    }

    @Test
    fun unitsAfterStartCodesGetLengthsInstead() {
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 0, 0, 1, 0x41, 3)
        assertContentEquals(byteArrayOf(0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 2, 0x41, 3), NalUnits.toLengthPrefixed(annexB))
        // Already with lengths: left as it is.
        val prefixed = byteArrayOf(0, 0, 0, 2, 0x41, 3)
        assertTrue(NalUnits.toLengthPrefixed(prefixed) === prefixed)
    }

    @Test
    fun anAvcSetupBecomesAnAvcCRecord() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1E, 0x11, 0x22)
        val pps = byteArrayOf(0x68, 0x33)
        val avcC = NalUnits.avcC(byteArrayOf(0, 0, 0, 1) + sps, byteArrayOf(0, 0, 0, 1) + pps)
        val expected = byteArrayOf(1, 0x64, 0, 0x1E, 0xFF.toByte(), 0xE1.toByte(), 0, 6) + sps + byteArrayOf(1, 0, 2) + pps
        assertContentEquals(expected, avcC)
    }

    @Test
    fun anHevcSetupIsReadForProfileLevelAndBitDepth() {
        // VPS, SPS and PPS of a 10-bit 640x360 clip from x265 (Main 10, level 2.1).
        val csd0 = hex("0000000140010c01ffff02200000030090000003000003003f959809") +
            hex("000000014201010220000003009000000300000300" + "3fa005020169365959a4932bc05a020000030002000003003010") +
            hex("000000014401c172b46240")
        val sps = NalUnits.hevcSps(NalUnits.split(csd0)[1])
        assertEquals(2, sps.profile) // Main 10
        assertEquals(63, sps.level)
        assertEquals(1, sps.chromaFormat)
        assertEquals(10, sps.lumaBitDepth)
        assertEquals(10, sps.chromaBitDepth)
        val hvcC = NalUnits.hvcC(csd0)
        assertEquals(1, hvcC[0].toInt())
        assertEquals(2, hvcC[1].toInt() and 0x1F)
        assertEquals(63, hvcC[12].toInt())
        assertEquals(0xF8 or 2, hvcC[17].toInt() and 0xFF) // luma bit depth 10
        assertEquals(3, hvcC[22].toInt()) // VPS, SPS and PPS arrays
    }

    @Test
    fun aPiecePointsEachRunAtItsSamples() {
        val video = Fmp4.Track(1, true, 90_000, Fmp4.avc1(640, 360, ByteArray(7)), 640, 360)
        val audio = Fmp4.Track(2, false, 48_000, Fmp4.mp4a(2, 48_000, byteArrayOf(0x11, 0x90.toByte())))
        // Decode order I P B with presentation 0, 80 ms, 40 ms.
        val pictures =
            listOf(
                Fmp4.Sample(byteArrayOf(1, 1, 1), 0, key = true),
                Fmp4.Sample(byteArrayOf(2, 2), 80_000, key = false),
                Fmp4.Sample(byteArrayOf(3), 40_000, key = false),
            )
        val sound = listOf(Fmp4.Sample(byteArrayOf(9, 9), 0, key = true), Fmp4.Sample(byteArrayOf(8), 21_333, key = true))
        val piece = Fmp4.segment(1, listOf(video to pictures, audio to sound), endUs = 120_000)
        val moofSize = int(piece, 0)
        assertEquals("moof", String(piece, 4, 4))
        assertEquals("mdat", String(piece, moofSize + 4, 4))
        assertContentEquals(byteArrayOf(1, 1, 1, 2, 2, 3, 9, 9, 8), piece.copyOfRange(moofSize + 8, piece.size))
        // The first trun's data offset lands on the first picture, the second's on the first sound frame.
        val truns = find(piece, "trun")
        assertEquals(moofSize + 8, int(piece, truns[0] + 16))
        assertEquals(moofSize + 8 + 6, int(piece, truns[1] + 16))
        // Picture timing: decode times 0, 3600, 7200; the B picture shows before the P one (offset -3600).
        val first = truns[0] + 20
        assertEquals(3600, int(piece, first)) // duration
        assertEquals(0, int(piece, first + 12)) // I: shown when decoded
        assertEquals(7200 - 3600, int(piece, first + 16 + 12)) // P: decoded second, shown third
        assertEquals(3600 - 7200, int(piece, first + 32 + 12)) // B: decoded third, shown second
        // The init segment names both tracks.
        val init = Fmp4.init(listOf(video, audio))
        assertEquals(2, find(init, "trak").size)
        assertEquals(1, find(init, "hdlr").count { String(init, it + 16, 4) == "vide" })
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun int(
        b: ByteArray,
        at: Int,
    ): Int = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
        ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /** Where each box of [type] starts, found by its name. */
    private fun find(
        b: ByteArray,
        type: String,
    ): List<Int> = (4..b.size - 4).filter { String(b, it, 4, Charsets.US_ASCII) == type }.map { it - 4 }
}
