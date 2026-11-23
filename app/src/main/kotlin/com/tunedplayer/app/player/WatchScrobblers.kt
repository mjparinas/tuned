package com.tunedplayer.app.player

import androidx.media3.common.Player
import com.tunedplayer.app.data.SimklAccount
import com.tunedplayer.app.data.TraktAccount
import com.tunedplayer.app.data.db.ItemEntity
import kotlinx.coroutines.CoroutineScope

/**
 * Trakt and Simkl scrobblers for the player, kept in one place so [PlayerActivity] grows by a few lines rather than by
 * each service (detekt's LargeClass limit on the activity).
 */
internal class WatchScrobblers(
    trakt: TraktAccount,
    simkl: SimklAccount,
    scope: CoroutineScope,
    current: () -> ItemEntity?,
    elsewhere: () -> Pair<Long, Long>? = { null },
) {
    private val trakt = TraktScrobbler(trakt, scope, current, elsewhere)
    private val simkl = SimklScrobbler(simkl, scope, current, elsewhere)

    fun attach(player: Player) {
        trakt.attach(player)
        simkl.attach(player)
    }

    fun release() {
        trakt.release()
        simkl.release()
    }
}
