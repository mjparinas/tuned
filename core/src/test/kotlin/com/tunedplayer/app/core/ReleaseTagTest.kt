package com.tunedplayer.app.core

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

    @Test
    fun aFailedCheckTriesAgainSoon() {
        val retry = ReleaseTag.RETRY_AFTER_MS
        // Offline when Tuned came to the front: the next time it does, a few minutes later, it asks again.
        assertTrue(ReleaseTag.checkDue(lastCheckMs = 1_000, nowMs = 1_000 + retry, force = false, failed = true))
        assertFalse(ReleaseTag.checkDue(lastCheckMs = 1_000, nowMs = 1_000 + retry, force = false, failed = false))
        assertFalse(ReleaseTag.checkDue(lastCheckMs = 1_000, nowMs = 1_000 + retry - 1, force = false, failed = true))
    }

    // The releases GitHub really lists for mjparinas/tuned (2026-11-17), newest first.
    private val v120 = ReleaseTag.Release("v1.2.0", draft = false, prerelease = false, apks = listOf("tuned.apk"))
    private val build4 = ReleaseTag.Release("v1.1.0-build.4", draft = false, prerelease = false, apks = listOf("tuned.apk"))

    @Test
    fun anOlderInstalledCopyIsOfferedTheNewRelease() {
        val next = ReleaseTag.Release("v1.2.1", draft = false, prerelease = false, apks = listOf("tuned.apk"))
        // 1.2.0 as installed (version code 1002000, read from the published APK) is offered 1.2.1.
        assertEquals("tuned.apk", ReleaseTag.apkToInstall(next, installedCode = 1_002_000))
        assertEquals("tuned.apk", ReleaseTag.apkToInstall(ReleaseTag.Release("v1.3.0", false, false, listOf("tuned.apk")), 1_002_000))
        assertEquals("tuned.apk", ReleaseTag.apkToInstall(ReleaseTag.Release("v2.0.0", false, false, listOf("tuned.apk")), 1_002_000))
        // Built from the old build-numbered tags (code 4), the plain tag is newer.
        assertEquals("tuned.apk", ReleaseTag.apkToInstall(v120, installedCode = 4))
        assertEquals("tuned.apk", ReleaseTag.apkToInstall(build4, installedCode = 3))
    }

    @Test
    fun theSameOrNewerInstalledCopyIsLeftAlone() {
        assertNull(ReleaseTag.apkToInstall(v120, installedCode = 1_002_000))
        assertNull(ReleaseTag.apkToInstall(v120, installedCode = 1_002_001))
        assertNull(ReleaseTag.apkToInstall(build4, installedCode = 1_002_000))
    }

    @Test
    fun draftsPrereleasesAndReleasesWithoutAnApkAreSkipped() {
        assertNull(ReleaseTag.apkToInstall(v120.copy(draft = true), installedCode = 4))
        assertNull(ReleaseTag.apkToInstall(v120.copy(prerelease = true), installedCode = 4))
        assertNull(ReleaseTag.apkToInstall(v120.copy(apks = listOf("app-release.aab", "notes.txt")), installedCode = 4))
        assertNull(ReleaseTag.apkToInstall(v120.copy(apks = emptyList()), installedCode = 4))
    }

    @Test
    fun picksTunedApkOverOtherApks() {
        val release = v120.copy(apks = listOf("tuned-debug.apk", "tuned.apk"))
        assertEquals("tuned.apk", ReleaseTag.apkToInstall(release, installedCode = 4))
        assertEquals("other.APK", ReleaseTag.apkToInstall(v120.copy(apks = listOf("other.APK")), installedCode = 4))
    }
}
