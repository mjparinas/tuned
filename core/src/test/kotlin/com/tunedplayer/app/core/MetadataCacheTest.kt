package com.tunedplayer.app.core

import com.tunedplayer.app.core.MetadataCache.Row
import com.tunedplayer.app.core.MetadataCache.Use
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataCacheTest {
    private val now = 100L * MetadataCache.KEEP_MS
    private val day = 24 * 3_600_000L

    @Test
    fun `a fresh answer comes from the device`() {
        assertEquals(Use.CACHED, MetadataCache.decide(Row(true, true, now - day), wantFull = true, now = now, online = true))
        assertEquals(Use.CACHED, MetadataCache.decide(Row(true, false, now - day), wantFull = false, now = now, online = true))
        assertEquals(Use.CACHED, MetadataCache.decide(Row(false, false, now - day), wantFull = true, now = now, online = true))
    }

    @Test
    fun `nothing on the device is fetched`() {
        assertEquals(Use.FETCH, MetadataCache.decide(null, wantFull = false, now = now, online = true))
    }

    @Test
    fun `a details page fetches the extras a poster's lookup didn't have`() {
        assertEquals(Use.FETCH, MetadataCache.decide(Row(true, false, now - day), wantFull = true, now = now, online = true))
    }

    @Test
    fun `an old answer shows at once and refreshes behind`() {
        val old = now - MetadataCache.KEEP_MS - day
        assertEquals(Use.CACHED_THEN_REFRESH, MetadataCache.decide(Row(true, true, old), wantFull = true, now = now, online = true))
        val oldMiss = now - MetadataCache.KEEP_MISSING_MS - day
        assertEquals(Use.CACHED_THEN_REFRESH, MetadataCache.decide(Row(false, false, oldMiss), wantFull = false, now = now, online = true))
    }

    @Test
    fun `offline never asks the network`() {
        assertEquals(Use.CACHED, MetadataCache.decide(null, wantFull = true, now = now, online = false))
        assertEquals(Use.CACHED, MetadataCache.decide(Row(true, false, now - day), wantFull = true, now = now, online = false))
        assertEquals(Use.CACHED, MetadataCache.decide(Row(true, true, 0), wantFull = true, now = now, online = false))
    }
}
