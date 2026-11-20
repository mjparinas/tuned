package com.tunedplayer.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CopyMergeTest {
    private fun copy(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val xtream = """{"server":"http://a.example","username":"me","password":"one"}"""
    private val link = """{"server":"","username":"","password":"","playlistUrl":"http://b.example/list.m3u"}"""
    private val portal = """{"server":"http://c.example","username":"00:1A:79:00:00:01","password":"","playlistUrl":"stalker:"}"""

    /** The bug this fixes: a TV that only watched something used to replace a playlist just added on the website. */
    @Test
    fun `a playlist added on the website survives a newer change on the device`() {
        val base = copy("""{"playlists":[$xtream],"profiles":[{"id":1,"seen":1}]}""")
        val local = copy("""{"playlists":[$xtream],"profiles":[{"id":1,"seen":2}]}""")
        val remote = copy("""{"playlists":[$xtream,$link],"profiles":[{"id":1,"seen":1}]}""")
        val merged = CopyMerge.merge(base, local, remote, remoteWins = false)
        assertEquals(copy("""{"playlists":[$xtream,$link],"profiles":[{"id":1,"seen":2}]}"""), merged)
    }

    @Test
    fun `a playlist removed on the website is removed here`() {
        val base = copy("""{"playlists":[$xtream,$link],"settings":{"theme":{"s":"DARK"}}}""")
        val local = copy("""{"playlists":[$xtream,$link],"settings":{"theme":{"s":"LIGHT"}}}""")
        val remote = copy("""{"playlists":[$xtream],"settings":{"theme":{"s":"DARK"}}}""")
        val merged = CopyMerge.merge(base, local, remote, remoteWins = true)
        assertEquals(copy("""{"playlists":[$xtream],"settings":{"theme":{"s":"LIGHT"}}}"""), merged)
    }

    @Test
    fun `settings join key by key and a clash goes to the newer side`() {
        val base = copy("""{"settings":{"a":{"b":true},"b":{"b":true}}}""")
        val local = copy("""{"settings":{"a":{"b":false},"b":{"b":false}}}""")
        val remote = copy("""{"settings":{"a":{"b":true},"b":{"b":true},"c":{"i":3}}}""")
        assertEquals(copy("""{"settings":{"a":{"b":false},"b":{"b":false},"c":{"i":3}}}"""), CopyMerge.merge(base, local, remote, true))
        val clash = copy("""{"settings":{"a":{"b":true},"b":{"i":9}}}""")
        assertEquals(copy("""{"settings":{"a":{"b":false},"b":{"i":9}}}"""), CopyMerge.merge(base, local, clash, true))
        assertEquals(copy("""{"settings":{"a":{"b":false},"b":{"b":false}}}"""), CopyMerge.merge(base, local, clash, false))
    }

    @Test
    fun `a password changed on the website wins over an untouched one here`() {
        val changed = xtream.replace("one", "two")
        val base = copy("""{"playlists":[$xtream,$portal]}""")
        val local = copy("""{"playlists":[$xtream,$portal,$link]}""")
        val remote = copy("""{"playlists":[$changed,$portal]}""")
        assertEquals(copy("""{"playlists":[$changed,$portal,$link]}"""), CopyMerge.merge(base, local, remote, false))
    }

    @Test
    fun `a fresh device keeps its own playlists and takes the backup's`() {
        val local = copy("""{"playlists":[$link],"settings":{"theme":{"s":"LIGHT"}}}""")
        val remote = copy("""{"partial":true,"playlists":[$xtream],"settings":{"theme":{"s":"DARK"}}}""")
        val merged = CopyMerge.merge(JsonObject(emptyMap()), local, remote, remoteWins = true)
        assertEquals(copy("""{"playlists":[$xtream,$link],"settings":{"theme":{"s":"DARK"}}}"""), merged)
    }

    /** The website's copy made from nothing has no profiles: the device's stay rather than being wiped. */
    @Test
    fun `parts the website's copy leaves out keep this device's`() {
        val local = copy("""{"playlists":[$link],"profiles":[{"id":1}],"intros":{"show":"1:2"}}""")
        val remote = copy("""{"partial":true,"playlists":[$xtream]}""")
        val merged = CopyMerge.merge(JsonObject(emptyMap()), local, remote, remoteWins = true)
        assertEquals(copy("""{"playlists":[$xtream,$link],"profiles":[{"id":1}],"intros":{"show":"1:2"}}"""), merged)
    }

    @Test
    fun `untouched here takes the backup as it is`() {
        val base = copy("""{"playlists":[$xtream],"favourites":[{"itemId":1}]}""")
        val remote = copy("""{"playlists":[$link]}""")
        assertEquals(remote, CopyMerge.merge(base, base, remote, remoteWins = true))
    }

    @Test
    fun `an empty list and a missing one are the same`() {
        assertTrue(CopyMerge.same(copy("""{"playlists":[$xtream],"lists":[]}"""), copy("""{"partial":true,"playlists":[$xtream]}""")))
    }
}
