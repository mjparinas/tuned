package com.tunedplayer.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HandoffAccountTest {
    private val copy = """{"version":2,"playlists":[]}""".toByteArray()

    @Test
    fun `a link rides along with the copy and comes back out`() {
        val sent = HandoffAccount.withLink(copy, "one-time-code")
        assertEquals("one-time-code", HandoffAccount.link(sent))
        assertEquals("2", Regex(""""version":(\d+)""").find(sent.decodeToString())?.groupValues?.get(1))
    }

    @Test
    fun `no link leaves the copy as it was`() {
        assertEquals(copy.decodeToString(), HandoffAccount.withLink(copy, null).decodeToString())
        assertNull(HandoffAccount.link(copy))
        assertNull(HandoffAccount.link("not json".toByteArray()))
    }
}
