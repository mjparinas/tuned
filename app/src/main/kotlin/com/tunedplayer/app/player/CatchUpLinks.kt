package com.tunedplayer.app.player

import com.tunedplayer.app.AppContainer
import com.tunedplayer.app.core.CatchUp
import com.tunedplayer.app.core.CatchUpTemplate
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.M3uCatchUp
import com.tunedplayer.app.core.XtreamUrls
import com.tunedplayer.app.data.Credentials
import com.tunedplayer.app.data.db.ItemEntity
import kotlinx.coroutines.flow.first
import java.time.DateTimeException
import java.time.ZoneId

/** Turns a past programme on a catch-up channel into something the player can play like a film. */
object CatchUpLinks {
    /**
     * The recording, as a one-off item: seekable, under the programme's title, and never saved to Continue Watching
     * (its id is [ID], which no playlist item has). Xtream logins use the server's timeshift link; plain M3U
     * playlists use the channel's own catch-up attributes ([CatchUpTemplate]).
     */
    suspend fun item(
        container: AppContainer,
        channel: ItemEntity,
        title: String,
        start: Long,
        stop: Long,
    ): ItemEntity? {
        val login = container.credentials.load() ?: return null
        val tz =
            container.settings.settings
                .first()
                .serverTimezone
        val zone =
            try {
                if (tz.isBlank()) ZoneId.systemDefault() else ZoneId.of(tz)
            } catch (_: DateTimeException) {
                ZoneId.systemDefault()
            }
        val url =
            if (login.kind == Credentials.Kind.Xtream) {
                val streamId = CatchUp.streamId(channel.url) ?: return null
                XtreamUrls.timeshift(login.server, login.username, login.password, streamId, start, CatchUp.minutes(start, stop), zone)
            } else {
                val spec =
                    container.db
                        .catchUp()
                        .get(CatchUp.key(channel.url))
                        ?.source
                        ?.let(M3uCatchUp::decode) ?: return null
                CatchUpTemplate.link(channel.url, spec, start, stop, System.currentTimeMillis(), zone) ?: return null
            }
        return channel.copy(id = ID, kind = ContentKind.MOVIE.code, name = title, groupName = channel.name, url = url)
    }

    /** A sentinel no 64-bit stream hash ([com.tunedplayer.app.core.StableId]) is in practice, so nothing is remembered for it. */
    const val ID = Long.MIN_VALUE
}
