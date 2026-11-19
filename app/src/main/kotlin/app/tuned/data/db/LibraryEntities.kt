package app.tuned.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.tuned.core.PlayGate
import app.tuned.data.AppSettings

/**
 * Favourites and watch history survive refreshes because they hold the stable id, not a row. Each profile keeps its
 * own ([profileId]); the DAOs fill it in with the profile in use, so callers leave it out.
 */
@Entity(tableName = "favourites", primaryKeys = ["profileId", "itemId"])
data class FavouriteEntity(
    val itemId: Long,
    val addedAt: Long,
    /** Where it sits in the Favourites list (Settings › Order favourites); new ones go to the end. */
    @ColumnInfo(defaultValue = "0") val position: Long = addedAt,
    @ColumnInfo(defaultValue = "1") val profileId: Long = Profiles.FIRST,
)

/** A favourite channel in Settings › Order favourites, the widget and Android TV's home screen. */
data class FavouriteRow(
    val id: Long,
    val name: String,
    val logo: String?,
    val tvgId: String?,
    /** For [PlayGate]: the widget and TV cards leave out what the profile and parental lock hide. */
    val groupName: String = "",
    val adult: Boolean = false,
) {
    fun allowed(settings: AppSettings): Boolean =
        PlayGate.allowed(adult, groupName, name, live = true, showAdult = settings.showAdult, kidsProfile = settings.kidsProfile)
}

@Entity(tableName = "history", primaryKeys = ["profileId", "itemId"], indices = [Index(value = ["watchedAt"])])
data class HistoryEntity(
    val itemId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,
    @ColumnInfo(defaultValue = "1") val profileId: Long = Profiles.FIRST,
)

/** A movie or episode to pick up where it was left. */
data class ContinueRow(
    val id: Long,
    val name: String,
    val logo: String?,
    val showName: String?,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * A film or a show saved to watch later (Melvin, 2026-11-17). [key] is `m:<item id>` for a film or `s:<show key>` for a
 * show. The title and artwork are kept here, so the list shows without looking anything up.
 */
@Entity(tableName = "watch_later", primaryKeys = ["profileId", "key"])
data class WatchLaterEntity(
    val key: String,
    val itemId: Long?,
    val showKey: String?,
    val title: String,
    val image: String?,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "1") val profileId: Long = Profiles.FIRST,
) {
    companion object {
        fun movieKey(itemId: Long) = "m:$itemId"

        fun showKey(showKey: String) = "s:$showKey"
    }
}

/**
 * An episode that was in the list the last time its show was opened (Melvin, 2026-11-17: "new episodes"). Episodes of
 * an opened show that aren't here arrived since, and show as new. Ids are stable across refreshes, so this survives them.
 */
@Entity(tableName = "seen_episodes", primaryKeys = ["profileId", "itemId"], indices = [Index("showKey")])
data class SeenEpisodeEntity(
    val itemId: Long,
    val showKey: String,
    @ColumnInfo(defaultValue = "1") val profileId: Long = Profiles.FIRST,
)

/**
 * One of the person's own lists of channels and films (Melvin, 2026-11-19: "similar to how spotify does it … favourite
 * is basically a playlist that you can add to by default but you can make your own"). Favourites stays the built-in
 * one ([FavouriteEntity]); these sit beside it, each for one profile. [id] is made at random when the list is created
 * rather than counted up, so the same list keeps the same id on every device that syncs it.
 */
@Entity(tableName = "lists", indices = [Index(value = ["profileId", "position"])])
data class ListEntity(
    @PrimaryKey val id: Long,
    val name: String,
    /** Where its chip sits among the person's lists; new ones go to the end. */
    val position: Long,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val profileId: Long = Profiles.FIRST,
)

/** A channel or film in one of the person's lists, by its stable id, so it survives refreshes like a favourite does. */
@Entity(tableName = "list_items", primaryKeys = ["listId", "itemId"])
data class ListItemEntity(
    val listId: Long,
    val itemId: Long,
    val addedAt: Long,
    /** Where it sits in the list; new ones go to the end. */
    val position: Long = addedAt,
)
