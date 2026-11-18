package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraktTest {
    @Test
    fun filmIsCleanedOfProviderTags() {
        assertEquals(
            Trakt.Target.Movie("Inception", 2010),
            Trakt.target(ContentKind.MOVIE, "EN - Inception (2010) [4K]", null, null, null),
        )
    }

    @Test
    fun episodeUsesTheShowSeasonAndNumber() {
        assertEquals(
            Trakt.Target.Episode("Breaking Bad", 2008, 2, 5),
            Trakt.target(ContentKind.SERIES, "Breaking Bad S02E05", "Breaking Bad (2008)", 2, 5),
        )
    }

    @Test
    fun episodeWithoutNumbersIsNotSent() {
        assertNull(Trakt.target(ContentKind.SERIES, "Some special", "Some show", null, null))
    }

    @Test
    fun liveChannelsAreNeverSent() {
        assertNull(Trakt.target(ContentKind.LIVE, "ESPN", null, null, null))
    }

    @Test
    fun progressIsAPercentage() {
        assertEquals(50.0, Trakt.progress(30_000, 60_000))
        assertEquals(33.33, Trakt.progress(1, 3))
        assertEquals(100.0, Trakt.progress(90_000, 60_000))
        assertEquals(0.0, Trakt.progress(-5, 60_000))
        assertEquals(0.0, Trakt.progress(10, 0))
    }

    @Test
    fun aSpotOfNothingIsNotSent() {
        assertFalse(Trakt.sendsSpot(0.0))
        assertFalse(Trakt.sendsSpot(0.44)) // Trakt answered "progress_too_low" for this
        assertTrue(Trakt.sendsSpot(1.0))
        assertTrue(Trakt.sendsSpot(100.0))
    }

    @Test
    fun pollAnswers() {
        assertEquals(Trakt.Poll.SIGNED_IN, Trakt.poll(200))
        assertEquals(Trakt.Poll.WAIT, Trakt.poll(400))
        assertEquals(Trakt.Poll.SLOW_DOWN, Trakt.poll(429))
        assertEquals(Trakt.Poll.EXPIRED, Trakt.poll(410))
        assertEquals(Trakt.Poll.DENIED, Trakt.poll(418))
        assertEquals(Trakt.Poll.FAILED, Trakt.poll(500))
    }

    @Test
    fun tokenIsRenewedADayEarly() {
        val day = 24 * 60 * 60 * 1000L
        assertFalse(Trakt.needsRefresh(expiresAtMs = 10 * day, nowMs = 8 * day))
        assertTrue(Trakt.needsRefresh(expiresAtMs = 10 * day, nowMs = 9 * day + 1))
    }

    @Test
    fun signInBodiesCarryTheClientIdWithoutASecret() {
        val bodies =
            listOf(
                Trakt.deviceTokenBody("id", "device"),
                Trakt.refreshBody("id", "refresh"),
                Trakt.revokeBody("id", "token"),
            )
        bodies.forEach { body ->
            assertEquals("id", body["client_id"])
            assertFalse("client_secret" in body)
            assertFalse("redirect_uri" in body)
        }
        assertEquals("refresh_token", Trakt.refreshBody("id", "refresh")["grant_type"])
    }
}
