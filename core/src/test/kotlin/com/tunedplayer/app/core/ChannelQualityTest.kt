package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelQualityTest {
    @Test
    fun `quality is read from the marks in a channel's name`() {
        assertTrue(ChannelQuality.UHD.matches("Sky Sports Main Event UHD"))
        assertTrue(ChannelQuality.UHD.matches("US| ESPN 4K"))
        assertTrue(ChannelQuality.FHD.matches("CA| TSN 1 FHD"))
        assertTrue(ChannelQuality.FHD.matches("BBC One 1080p"))
        assertTrue(ChannelQuality.FHD.matches("Canal+ Full HD"))
        assertTrue(ChannelQuality.HD.matches("CNN HD"))
        assertTrue(ChannelQuality.HD.matches("ESPN (720p)"))
        assertTrue(ChannelQuality.SD.matches("TF1 SD"))
    }

    @Test
    fun `marks count only as whole words`() {
        assertFalse(ChannelQuality.HD.matches("TSN 1 FHD"))
        assertFalse(ChannelQuality.HD.matches("Sky Sports UHD"))
        assertFalse(ChannelQuality.UHD.matches("Channel 4"))
        assertFalse(ChannelQuality.SD.matches("SDTV Classics"))
        assertFalse(ChannelQuality.HD.matches("Shadow TV"))
    }

    @Test
    fun `the full-text query lists every word, phrases quoted`() {
        assertEquals("fhd OR 1080p OR 1080i OR fullhd OR \"full hd\"", ChannelQuality.FHD.fullText)
    }
}
