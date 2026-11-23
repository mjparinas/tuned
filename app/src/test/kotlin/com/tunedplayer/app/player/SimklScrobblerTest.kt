package com.tunedplayer.app.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.Simkl
import com.tunedplayer.app.data.db.ItemEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/** What Simkl hears when the person moves on to the next episode before this one ends. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class SimklScrobblerTest {
    private val sent = mutableListOf<Pair<String, Double>>()
    private var position = 0L
    private var index = 0
    private val length = 2_400_000L // 40 minutes

    private val player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlayWhenReady" -> true
                "getPlaybackState" -> Player.STATE_READY
                "getCurrentPosition" -> position
                "getDuration" -> length
                else -> defaultFor(method.returnType)
            }
        } as Player

    private val episodes = listOf(episode(1, 1), episode(2, 2))

    private val scrobbler =
        SimklScrobbler(
            signedIn = { true },
            scrobble = { action, _: Simkl.Target, progress -> sent += action to progress },
            scope = TestScope(UnconfinedTestDispatcher()),
            current = { episodes[index] },
        )

    @Test
    fun `next episode near the end tells Simkl how far the last one got`() {
        scrobbler.attach(player)
        scrobbler.onPlaybackStateChanged(Player.STATE_READY)
        // Watched to the credits, then Next: the player is on episode 2 before it says it moved.
        position = 2_280_000L // 95%
        scrobbler.onPositionDiscontinuity(at(0, 2_280_000L), at(1, 0L), Player.DISCONTINUITY_REASON_SEEK)
        index = 1
        position = 0L
        scrobbler.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        assertEquals("stop" to 95.0, sent.last())
    }

    private fun at(
        item: Int,
        positionMs: Long,
    ) = Player.PositionInfo(null, item, null, null, item, positionMs, positionMs, -1, -1)

    private fun episode(
        id: Long,
        number: Int,
    ) = ItemEntity(
        id = id,
        kind = ContentKind.SERIES.code,
        name = "Show S01E0$number",
        searchName = "show",
        groupName = "Shows",
        region = null,
        adult = false,
        logo = null,
        url = "http://h/series/u/p/$id.mkv",
        tvgId = null,
        showKey = "show",
        showName = "Show",
        season = 1,
        episode = number,
        sortOrder = number,
    )

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}
