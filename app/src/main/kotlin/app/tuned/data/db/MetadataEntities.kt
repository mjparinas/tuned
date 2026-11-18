package app.tuned.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What TMDB said about a film or show, kept on the device so details and ratings load without the network.
 * [full] is false when only the search answer (rating, summary, artwork) is known, true once the details page has
 * also fetched genres, runtime, cast and trailer. A row with [found] false remembers that nothing matched.
 */
@Entity(tableName = "metadata")
data class MetadataEntity(
    @PrimaryKey val cacheKey: String,
    val found: Boolean,
    val full: Boolean,
    val tmdbId: Long,
    val title: String,
    val year: String?,
    val overview: String?,
    val rating: Double?,
    val poster: String?,
    val backdrop: String?,
    /** Comma-separated. */
    val genres: String,
    val runtime: String?,
    /**
     * One person per line: name, character, photo link and TMDB person id, tab-separated. A person's own row
     * ("person|<id>", see PeopleRepository) keeps their credits here as JSON instead.
     */
    val cast: String,
    val trailer: String?,
    val fetchedAt: Long,
)

/**
 * One season of a show on TMDB, keyed "tmdbId|season": the episodes as JSON (name, summary, runtime and screenshot
 * link), or nothing found. Kept so a season seen once shows its screenshots and names from the device after that.
 */
@Entity(tableName = "tmdb_seasons")
data class TmdbSeasonEntity(
    @PrimaryKey val cacheKey: String,
    val found: Boolean,
    val episodes: String,
    val fetchedAt: Long,
)
