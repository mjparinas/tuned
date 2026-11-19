package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvHandoffTest {
    // Made by the phone page's own code (devices/public/tv/seal.js) in Node, so the two can't drift apart.
    private val pagePkcs8 =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgDSqL8r6_nu-GpdJJwEdeYlTsXYUMC2mzCvGUY7DtMWShRANCAASDcBC6JkDffYNNxmVrpGb7" +
            "xloNN-u9EyRQdfS4qZuS3B8GUzBVh-_M5ONmmwBVbAYcEadvWV0Ma5o0N8y2vhS1"
    private val pageKey = "BINwELomQN99g03GZWukZvvGWg03670TJFB19Lipm5LcHwZTMFWH78zk42abAFVsBhwRp29ZXQxrmjQ3zLa-FLU"
    private val pageBox =
        "AQQON53YdqkgihVsL_MsDNn7z7thfe5f1NTChVPYk1hn65o1d5W4wp8ScpvE1bnwwvN0tBEKWOKx_Y47nqLgC4DfVXHdHzNUM3jrE-eBsBAEYw7vZxAt9kIf" +
            "4797zHv_ygb5g-bv5Ky4D8GyqDXEOAqoXkmxBNf9X64Srvk-12hjdRCZBwQlA2O13MPyy8PPof1Xjtj9-OSuX0PEvwPpK7yKjrDX0o1L2D1hch11pTBpldO27-" +
            "5MxsZCnOY70X-UaJj36kqzuEkRfy2HYUMlz-3EAmzFuTRHfQ"
    private val pageJson =
        """{"version":1,"playlists":[{"server":"http://tv.example.com:8080","username":"me","password":"p@ss","name":"Living room"}]}"""

    @Test
    fun `a box sealed by the phone page opens on the tv`() {
        val keys = TvHandoff.Keys.of(TvHandoff.unb64(pagePkcs8), TvHandoff.unb64(pageKey))
        val plain = TvHandoff.open(TvHandoff.unb64(pageBox), "K7QF29MD", keys)
        assertEquals(pageJson, TvHandoff.unpack(plain).decodeToString())
    }

    @Test
    fun `the check digits match the phone page's`() {
        assertEquals("1068", TvHandoff.checkDigits(TvHandoff.unb64(pageKey)))
    }

    @Test
    fun `the app's box opens with the tv's keys, and a sync copy is gzipped`() {
        val tv = TvHandoff.newKeys()
        val json = """{"version":1,"favourites":[${(1..500).joinToString { """{"itemId":$it,"addedAt":0,"position":$it}""" }}]}"""
        val packed = TvHandoff.pack(json.toByteArray())
        assertTrue(packed.size < json.length / 3, "favourites shrink a lot")
        val box = TvHandoff.seal(packed, TvHandoff.Target("K7QF29MD", tv.publicKey))
        assertEquals(json, TvHandoff.unpack(TvHandoff.open(box, "K7QF29MD", tv)).decodeToString())
    }

    @Test
    fun `a box for another code or tv, or one changed on the way, doesn't open`() {
        val tv = TvHandoff.newKeys()
        val box = TvHandoff.seal(TvHandoff.pack("{}".toByteArray()), TvHandoff.Target("K7QF29MD", tv.publicKey))
        assertFailsWith<TvHandoff.NotOpened> { TvHandoff.open(box, "K7QF29ME", tv) }
        assertFailsWith<TvHandoff.NotOpened> { TvHandoff.open(box, "K7QF29MD", TvHandoff.newKeys()) }
        val changed = box.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<TvHandoff.NotOpened> { TvHandoff.open(changed, "K7QF29MD", tv) }
        assertFailsWith<TvHandoff.NotOpened> { TvHandoff.open(ByteArray(10), "K7QF29MD", tv) }
    }

    @Test
    fun `the qr link carries the code and key, and reads back`() {
        val tv = TvHandoff.newKeys()
        val link = TvHandoff.link("https://tuned-devices.vercel.app/", "K7QF29MD", tv.publicKey)
        assertTrue(link.startsWith("https://tuned-devices.vercel.app/tv#K7QF29MD."))
        assertEquals(TvHandoff.Target("K7QF29MD", tv.publicKey), TvHandoff.parseLink(link))
        assertEquals(
            TvHandoff.Target("K7QF29MD", tv.publicKey),
            TvHandoff.parseLink(link.replace("https://tuned-devices.vercel.app/", "tuned://")),
        )
    }

    @Test
    fun `a link with a key off the curve is refused`() {
        val off =
            ByteArray(65).also {
                it[0] = 4
                it[1] = 1
            }
        assertNull(TvHandoff.parseLink("tuned://tv#K7QF29MD.${TvHandoff.b64(off)}"))
        assertNull(TvHandoff.parseLink("tuned://tv#K7QF29MD"))
        assertNull(TvHandoff.parseLink("https://tuned-devices.vercel.app/tv"))
        assertFailsWith<TvHandoff.NotOpened> { TvHandoff.seal(byteArrayOf(0), TvHandoff.Target("K7QF29MD", off)) }
    }

    @Test
    fun `codes are read however they're typed`() {
        assertEquals("K7QF29MD", TvHandoff.normalizeCode(" k7qf-29md "))
        assertNull(TvHandoff.normalizeCode("K7QF-29M0"))
        assertNull(TvHandoff.normalizeCode("K7QF"))
        assertEquals("K7QF-29MD", TvHandoff.displayCode("K7QF29MD"))
    }

    @Test
    fun `a payload that isn't ours is refused`() {
        assertFailsWith<TvHandoff.NotOpened> { TvHandoff.unpack(byteArrayOf()) }
        assertFailsWith<TvHandoff.NotOpened> { TvHandoff.unpack(byteArrayOf(7, 1, 2)) }
        assertFailsWith<TvHandoff.NotOpened> { TvHandoff.unpack(byteArrayOf(1, 1, 2)) }
        assertContentEquals("{}".toByteArray(), TvHandoff.unpack(TvHandoff.pack("{}".toByteArray())))
        assertNotNull(TvHandoff.newKeys().publicKey.takeIf { it.size == 65 })
    }
}
