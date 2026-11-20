package com.tunedplayer.app.data

import com.tunedplayer.app.core.ChannelShare
import com.tunedplayer.app.core.ContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The copy the app keeps in the Tuned account reads back into channels and a guide this device can use. */
class AccountChannelsTest {
    @Test
    fun theAccountsCopyReadsBackWithThisDevicesLogin() {
        val now = 1_800_000_000_000L
        val channels =
            listOf(
                ChannelShare.Channel("CBC Toronto", "Canada", "http://logo/cbc.png", "cbc.ca", 1, link("101")),
                ChannelShare.Channel("TSN 1", "Sports", null, null, 2, link("102")),
            )
        val shows = listOf(ChannelShare.Show("cbc.ca", now, now + HOUR, "The National"))
        val text = ChannelShare.payload("0123456789abcdef", channels, shows, now)

        val copy = AccountChannels.read(text, "sam", "s3cret")

        assertEquals(listOf("CBC Toronto", "TSN 1"), copy.channels.map { it.name })
        assertEquals("http://p.example:8080/live/sam/s3cret/101.ts", copy.channels[0].url)
        assertTrue(copy.channels.all { it.kind == ContentKind.LIVE })
        assertEquals("cbc.ca", copy.channels[0].tvgId)
        assertNull(copy.channels[1].tvgId)
        assertNull(copy.channels[1].logo)
        assertEquals(listOf(0, 1), copy.channels.map { it.order })
        val programme = copy.guide.single()
        assertEquals("cbc.ca", programme.channelId)
        assertEquals(now, programme.start)
        assertEquals("The National", programme.title)
    }

    @Test
    fun aDamagedRowIsSkippedNotFatal() {
        val text =
            """{"v":1,"playlist":"0123456789abcdef","savedAt":0,""" +
                """"channels":[["Only a name"],["Good","G","","",1,"http://x/1.ts"]],"guide":{}}"""

        val copy = AccountChannels.read(text, "sam", "s3cret")

        assertEquals(listOf("Good"), copy.channels.map { it.name })
    }

    private fun link(id: String) = ChannelShare.hideLogin("http://p.example:8080/live/sam/s3cret/$id.ts", "sam", "s3cret")

    private companion object {
        const val HOUR = 3_600_000L
    }
}
