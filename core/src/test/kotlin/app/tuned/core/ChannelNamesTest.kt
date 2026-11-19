package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelNamesTest {
    @Test
    fun `country and language tags come off the front`() {
        assertEquals("CP24", ChannelNames.short("CA| CP24"))
        assertEquals("Thames One", ChannelNames.short("|UK| Thames One"))
        assertEquals("Prairie Public", ChannelNames.short("US: Prairie Public"))
        assertEquals("Rheinwelle", ChannelNames.short("[DE] Rheinwelle"))
        assertEquals("Maple News 24", ChannelNames.short("CA - Maple News 24"))
        assertEquals("Adria 1", ChannelNames.short("EXYU | Adria 1"))
        assertEquals("Rink Sports 1", ChannelNames.short("|CA| EN: Rink Sports 1"))
    }

    @Test
    fun `picture quality comes off the end`() {
        assertEquals("Rink Sports 1", ChannelNames.short("CA| Rink Sports 1 HD"))
        assertEquals("Wild Planet", ChannelNames.short("Wild Planet FHD"))
        assertEquals("Wild Planet", ChannelNames.short("Wild Planet (4K)"))
        assertEquals("Wild Planet", ChannelNames.short("Wild Planet [UHD] 60FPS"))
        assertEquals("Thames One", ChannelNames.short("UK: Thames One ᴴᴰ"))
    }

    @Test
    fun `real names stay whole`() {
        assertEquals("CBC - Toronto", ChannelNames.short("CBC - Toronto"))
        assertEquals("ABC: Live", ChannelNames.short("ABC: Live"))
        assertEquals("Maple News 24", ChannelNames.short("Maple News 24"))
        assertEquals("Shadow", ChannelNames.short("Shadow"))
        assertEquals("CA|", ChannelNames.short("CA|"))
        assertEquals("HD", ChannelNames.short("HD"))
    }
}
