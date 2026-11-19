package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserAgentsTest {
    @Test
    fun blankIsTuned() {
        assertEquals(UserAgents.TUNED, UserAgents.presetFor(""))
        assertEquals(UserAgents.TUNED, UserAgents.presetFor("  "))
        assertEquals("tuned", UserAgents.presets.first().id)
    }

    @Test
    fun presetValuesMatchTheirPreset() {
        UserAgents.presets.forEach { assertEquals(it, UserAgents.presetFor(it.value)) }
        assertEquals("vlc", UserAgents.presetFor(" VLC/3.0.21 LibVLC/3.0.21 ")?.id)
    }

    @Test
    fun anythingElseStaysCustom() {
        assertNull(UserAgents.presetFor("MyBox/1.0"))
        assertNull(UserAgents.presetFor("vlc/3.0.21 libvlc/3.0.21"))
    }

    @Test
    fun idsAndValuesAreUnique() {
        val presets = UserAgents.presets
        assertEquals(presets, presets.distinctBy { it.id })
        assertEquals(presets, presets.distinctBy { it.value })
    }
}
