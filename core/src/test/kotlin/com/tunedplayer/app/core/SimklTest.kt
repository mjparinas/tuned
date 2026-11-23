package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimklTest {
    @Test
    fun filmIsCleanedOfProviderTags() {
        assertEquals(
            Simkl.Target.Movie("Inception", 2010),
            Simkl.target(ContentKind.MOVIE, "EN - Inception (2010) [4K]", null, null, null),
        )
    }

    @Test
    fun episodeUsesTheShowSeasonAndNumber() {
        assertEquals(
            Simkl.Target.Episode("Breaking Bad", 2008, 2, 5),
            Simkl.target(ContentKind.SERIES, "Breaking Bad S02E05", "Breaking Bad (2008)", 2, 5),
        )
    }

    @Test
    fun episodeWithoutNumbersIsNotSent() {
        assertNull(Simkl.target(ContentKind.SERIES, "Some special", "Some show", null, null))
    }

    @Test
    fun liveChannelsAreNeverSent() {
        assertNull(Simkl.target(ContentKind.LIVE, "ESPN", null, null, null))
    }

    @Test
    fun progressIsAPercentage() {
        assertEquals(50.0, Simkl.progress(30_000, 60_000))
        assertEquals(33.33, Simkl.progress(1, 3))
        assertEquals(100.0, Simkl.progress(90_000, 60_000))
        assertEquals(0.0, Simkl.progress(-5, 60_000))
        assertEquals(0.0, Simkl.progress(10, 0))
    }

    @Test
    fun aSpotOfNothingIsNotSent() {
        assertFalse(Simkl.sendsSpot(0.0))
        assertFalse(Simkl.sendsSpot(0.44))
        assertTrue(Simkl.sendsSpot(1.0))
        assertTrue(Simkl.sendsSpot(100.0))
    }

    @Test
    fun pollAnswers() {
        assertEquals(Simkl.Poll.SIGNED_IN, Simkl.poll(200, null))
        assertEquals(Simkl.Poll.WAIT, Simkl.poll(400, "authorization_pending"))
        assertEquals(Simkl.Poll.SLOW_DOWN, Simkl.poll(400, "slow_down"))
        assertEquals(Simkl.Poll.EXPIRED, Simkl.poll(400, "expired_token"))
        assertEquals(Simkl.Poll.FAILED, Simkl.poll(401, "invalid_client"))
        assertEquals(Simkl.Poll.FAILED, Simkl.poll(500, null))
    }

    @Test
    fun tokenIsRenewedADayEarly() {
        val day = 24 * 60 * 60 * 1000L
        assertFalse(Simkl.needsRefresh(expiresAtMs = 10 * day, nowMs = 8 * day))
        assertTrue(Simkl.needsRefresh(expiresAtMs = 10 * day, nowMs = 9 * day + 1))
    }

    @Test
    fun signInBodiesCarryTheClientIdWithoutASecret() {
        val bodies =
            listOf(
                Simkl.deviceCodeBody("id"),
                Simkl.deviceTokenBody("id", "device"),
                Simkl.refreshBody("id", "refresh"),
                Simkl.revokeBody("id", "token"),
            )
        bodies.forEach { body ->
            assertEquals("id", body["client_id"])
            assertFalse("client_secret" in body)
            assertFalse("redirect_uri" in body)
        }
        assertEquals(Simkl.SCOPE, Simkl.deviceCodeBody("id")["scope"])
        assertEquals(Simkl.DEVICE_GRANT, Simkl.deviceTokenBody("id", "device")["grant_type"])
        assertEquals("refresh_token", Simkl.refreshBody("id", "refresh")["grant_type"])
    }
}
