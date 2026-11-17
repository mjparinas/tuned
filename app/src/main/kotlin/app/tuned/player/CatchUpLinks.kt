package app.tuned.player

import app.tuned.AppContainer
import app.tuned.core.CatchUp
import app.tuned.core.ContentKind
import app.tuned.core.XtreamUrls
import app.tuned.data.db.ItemEntity
import kotlinx.coroutines.flow.first
import java.time.DateTimeException
import java.time.ZoneId

/** Turns a past programme on a catch-up channel into something the player can play like a film. */
object CatchUpLinks {
    /**
     * The recording, as a one-off item: seekable, under the programme's title, and never saved to Continue Watching
     * (its id is [ID], which no playlist item has).
     */
    suspend fun item(
        container: AppContainer,
        channel: ItemEntity,
        title: String,
        start: Long,
        stop: Long,
    ): ItemEntity? {
        val login = container.credentials.load()?.takeIf { it.playlistUrl.isBlank() } ?: return null
        val streamId = CatchUp.streamId(channel.url) ?: return null
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
        val url = XtreamUrls.timeshift(login.server, login.username, login.password, streamId, start, CatchUp.minutes(start, stop), zone)
        return channel.copy(id = ID, kind = ContentKind.MOVIE.code, name = title, groupName = channel.name, url = url)
    }

    /** A sentinel no 64-bit stream hash ([app.tuned.core.StableId]) is in practice, so nothing is remembered for it. */
    const val ID = Long.MIN_VALUE
}
