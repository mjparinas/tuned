package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseTagTest {
    @Test
    fun tagsAreThePlainVersion() {
        val tag = ReleaseTag.of("1.2.0")
        assertEquals("v1.2.0", tag)
        assertEquals("1.2.0", ReleaseTag.versionName(tag))
        assertEquals(1_002_000, ReleaseTag.versionCode(tag))
        assertEquals(1_002_003, ReleaseTag.code("1.2.3"))
    }

    @Test
    fun theCodeGrowsWithTheVersion() {
        assertTrue(ReleaseTag.isNewer("v1.2.1", installedCode = 1_002_000))
        assertTrue(ReleaseTag.isNewer("v1.10.0", installedCode = 1_009_099))
        assertFalse(ReleaseTag.isNewer("v1.2.0", installedCode = 1_002_000))
        assertFalse(ReleaseTag.isNewer("v1.1.9", installedCode = 1_002_000))
    }

    @Test
    fun oldBuildTagsStillRead() {
        assertEquals(4, ReleaseTag.versionCode("v1.1.0-build.4"))
        assertEquals("1.1.0", ReleaseTag.versionName("v1.1.0-build.4"))
        // A copy built from an old tag (code 4) sees every plain tag as newer.
        assertTrue(ReleaseTag.isNewer("v1.1.1", installedCode = 4))
    }

    @Test
    fun ignoresOtherTags() {
        assertNull(ReleaseTag.versionCode("nightly"))
        assertNull(ReleaseTag.versionCode("v1.2"))
        assertNull(ReleaseTag.code("1.1000.0"))
        assertFalse(ReleaseTag.isNewer("nightly", installedCode = 1))
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
