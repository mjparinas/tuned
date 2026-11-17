package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseTagTest {
    @Test
    fun roundTrips() {
        val tag = ReleaseTag.of("0.1.0", 42)
        assertEquals("v0.1.0-build.42", tag)
        assertEquals(42, ReleaseTag.versionCode(tag))
        assertEquals("0.1.0", ReleaseTag.versionName(tag))
    }

    @Test
    fun comparesTheCodeNotTheName() {
        assertTrue(ReleaseTag.isNewer("v0.1.0-build.43", installedCode = 42))
        assertFalse(ReleaseTag.isNewer("v0.2.0-build.42", installedCode = 42))
        assertFalse(ReleaseTag.isNewer("v0.1.0-build.7", installedCode = 42))
    }

    @Test
    fun ignoresOtherTags() {
        assertNull(ReleaseTag.versionCode("v1.0.0"))
        assertNull(ReleaseTag.versionCode("nightly"))
        assertFalse(ReleaseTag.isNewer("v1.0.0", installedCode = 1))
    }

    @Test
    fun checksAgainWhileTheAppStaysOpen() {
        val hour = ReleaseTag.CHECK_EVERY_MS
        // The bug: a copy left running never looked again. Coming back after an hour must check.
        assertTrue(ReleaseTag.checkDue(lastCheckMs = 1_000, nowMs = 1_000 + hour, force = false))
        assertTrue(ReleaseTag.checkDue(lastCheckMs = 0, nowMs = 5, force = false))
        assertFalse(ReleaseTag.checkDue(lastCheckMs = 1_000, nowMs = 1_000 + hour - 1, force = false))
        assertTrue(ReleaseTag.checkDue(lastCheckMs = 1_000, nowMs = 1_001, force = true))
        assertTrue(ReleaseTag.checkDue(lastCheckMs = 5_000, nowMs = 1_000, force = false))
    }
}
