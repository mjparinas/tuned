package com.tunedplayer.app.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageRulesTest {
    @Test
    fun onOutsideEuropeOffInsideItAndOffWhenNothingIsKnown() {
        assertTrue(UsageRules.onByDefault(listOf("ca", "CA"), "America/Toronto"))
        assertTrue(UsageRules.onByDefault(listOf("US"), null))
        assertTrue(UsageRules.onByDefault(emptyList(), "Asia/Manila"))
        assertFalse(UsageRules.onByDefault(listOf("DE"), "America/Toronto"))
        assertFalse(UsageRules.onByDefault(listOf("gb"), null))
        assertFalse(UsageRules.onByDefault(listOf("CH"), null))
        assertFalse(UsageRules.onByDefault(listOf("NO"), null))
        assertFalse(UsageRules.onByDefault(listOf("RE"), null))
        // A Canadian SIM in a phone set to Paris time: any sign inside keeps it off.
        assertFalse(UsageRules.onByDefault(listOf("CA"), "Europe/Paris"))
        assertFalse(UsageRules.onByDefault(listOf("US"), "Atlantic/Canary"))
        assertFalse(UsageRules.onByDefault(listOf(null, "", " "), ""))
        assertFalse(UsageRules.onByDefault(emptyList(), null))
    }

    @Test
    fun installIdsAreRandomHex() {
        val id = UsageRules.newInstallId(Random(1))
        assertEquals(32, id.length)
        assertTrue(UsageRules.isInstallId(id))
        assertTrue(UsageRules.newInstallId() != UsageRules.newInstallId())
        assertFalse(UsageRules.isInstallId("ABC"))
        assertFalse(UsageRules.isInstallId("g".repeat(32)))
        assertFalse(UsageRules.isInstallId(null))
    }

    @Test
    fun theEventNamesMatchTheServersAllowList() {
        // devices/lib/admin/analytics.mjs EVENTS; nothing named after what's watched or typed.
        assertEquals(18, UsageRules.EVENTS.size)
        val banned = listOf("channel", "title", "url", "server", "login", "password", "search_query", "playlist_url")
        assertTrue(UsageRules.EVENTS.none { e -> banned.any { it in e } })
    }

    @Test
    fun sendsWhenTheGapHasPassedOrABatchIsFull() {
        val hour = 60 * 60_000L
        assertFalse(UsageRules.dueToSend(0, 0, 10 * hour, 60))
        assertFalse(UsageRules.dueToSend(5, hour, hour + 59 * 60_000L, 60))
        assertTrue(UsageRules.dueToSend(5, hour, 2 * hour, 60))
        assertTrue(UsageRules.dueToSend(100, hour, hour, 60))
    }

    @Test
    fun flagsKeepTheirDefaultsForAnythingWrong() {
        assertEquals(RemoteFlags(), RemoteFlags.from(emptyMap()))
        val f =
            RemoteFlags.from(
                mapOf(
                    "tmdb" to false,
                    "trakt" to "false",
                    "cast" to false,
                    "web_player" to true,
                    "usage_batch_minutes" to 30,
                    "min_version_code" to 1_013_000.0,
                    "unknown" to true,
                ),
            )
        assertFalse(f.tmdb)
        assertTrue(f.trakt)
        assertFalse(f.cast)
        assertTrue(f.webPlayer)
        assertFalse(RemoteFlags().webPlayer)
        assertEquals(30, f.usageBatchMinutes)
        assertEquals(1_013_000, f.minVersionCode)
        assertEquals(60, RemoteFlags.from(mapOf("usage_batch_minutes" to 5)).usageBatchMinutes)
        assertEquals(60, RemoteFlags.from(mapOf("usage_batch_minutes" to 30.5)).usageBatchMinutes)
        assertEquals(0, RemoteFlags.from(mapOf("min_version_code" to -1)).minVersionCode)
    }

    @Test
    fun tooOldOnlyBelowAMinimumThatIsSet() {
        assertFalse(RemoteFlags().tooOld(1))
        assertTrue(RemoteFlags(minVersionCode = 1_013_000).tooOld(1_012_000))
        assertFalse(RemoteFlags(minVersionCode = 1_013_000).tooOld(1_013_000))
    }
}
