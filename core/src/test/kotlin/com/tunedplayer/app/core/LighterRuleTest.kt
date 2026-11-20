package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LighterRuleTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `a capable device with no freezes stays as it is`() {
        assertFalse(LighterRule.auto(lowRam = false, freezeTimesMs = emptyList(), nowMs = now))
    }

    @Test
    fun `a low-memory device is lighter from the start`() {
        assertTrue(LighterRule.auto(lowRam = true, freezeTimesMs = emptyList(), nowMs = now))
    }

    @Test
    fun `one freeze is bad luck, two in a week is the device`() {
        assertFalse(LighterRule.auto(false, listOf(now - day), now))
        assertTrue(LighterRule.auto(false, listOf(now - day, now - 6 * day), now))
    }

    @Test
    fun `freezes older than a week no longer count`() {
        assertFalse(LighterRule.auto(false, listOf(now - day, now - 8 * day), now))
    }

    @Test
    fun `a time from a wrong clock in the future does not count`() {
        assertFalse(LighterRule.auto(false, listOf(now - day, now + 30 * day), now))
    }
}
