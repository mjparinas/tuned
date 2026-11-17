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
}
