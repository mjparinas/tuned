package com.tunedplayer.app.ui.browse

import com.tunedplayer.app.core.ChannelQuality
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.LiveTopic
import com.tunedplayer.app.core.ProgrammeGenre
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one profile last chose on the browse tabs: each tab's chip (All, Favourites, a list, Recent or a category) and
 * Live TV's quick filters. Kept by [com.tunedplayer.app.data.BrowseFilterStore] until changed, even after the app is
 * closed. The sort is a setting of its own (movie_sort, show_sort) and is not kept here.
 */
data class SavedFilters(
    val chips: Map<ContentKind, Filter> = emptyMap(),
    val live: LiveQuick = LiveQuick(),
) {
    fun chip(kind: ContentKind): Filter = chips[kind] ?: Filter.All

    companion object {
        private const val TAG = "SavedFilters"
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(saved: SavedFilters): String = json.encodeToString(
            Stored.serializer(),
            Stored(
                chips = saved.chips.filterValues { it != Filter.All }.map { (k, f) -> k.code to encodeChip(f) }.toMap(),
                // Adult channels need the parental PIN each time, so the Adult filter is never kept.
                topic = saved.live.topic?.takeUnless { it.isAdult }?.name,
                country = (saved.live.place as? LivePlace.Country)?.tag,
                language = (saved.live.place as? LivePlace.Language)?.code,
                languageTags = (saved.live.place as? LivePlace.Language)?.tags.orEmpty(),
                quality = saved.live.quality?.name,
                onNow = saved.live.onNow?.name,
                withGuide = saved.live.withGuide,
                catchUp = saved.live.catchUp,
                watched = saved.live.watched,
            ),
        )

        /**
         * Reads [encode]'s text back. Anything unreadable (an older or damaged copy, a filter since removed) comes back
         * as nothing chosen, never a failure, so the tabs always open.
         */
        fun decode(text: String?): SavedFilters {
            if (text.isNullOrBlank()) return SavedFilters()
            val stored = attempt(TAG, "Couldn't read the saved filters") { json.decodeFromString(Stored.serializer(), text) }.getOrNull()
            return stored?.toSaved() ?: SavedFilters()
        }

        internal fun encodeChip(f: Filter): String = when (f) {
            Filter.All -> "all"
            Filter.Favourites -> "fav"
            Filter.Recent -> "recent"
            is Filter.Group -> "g:" + f.name
            is Filter.OwnList -> "l:" + f.id
        }

        internal fun decodeChip(s: String): Filter? = when {
            s == "all" -> Filter.All
            s == "fav" -> Filter.Favourites
            s == "recent" -> Filter.Recent
            s.startsWith("g:") && s.length > 2 -> Filter.Group(s.substring(2))
            s.startsWith("l:") -> s.substring(2).toLongOrNull()?.let { Filter.OwnList(it) }
            else -> null
        }

        private fun Stored.toSaved(): SavedFilters {
            val kinds =
                chips
                    .mapNotNull { (code, chip) ->
                        val kind = ContentKind.entries.firstOrNull { it.code == code } ?: return@mapNotNull null
                        val filter = decodeChip(chip) ?: return@mapNotNull null
                        // Recent is Live TV's alone.
                        if (filter == Filter.Recent && kind != ContentKind.LIVE) null else kind to filter
                    }.toMap()
            val place =
                when {
                    country != null -> LivePlace.Country(country)
                    language != null && languageTags.isNotEmpty() -> LivePlace.Language(language, languageTags)
                    else -> null
                }
            return SavedFilters(
                chips = kinds,
                live =
                    LiveQuick(
                        topic = LiveTopic.entries.firstOrNull { it.name == topic && !it.isAdult },
                        place = place,
                        quality = ChannelQuality.entries.firstOrNull { it.name == quality },
                        onNow = ProgrammeGenre.entries.firstOrNull { it.name == onNow },
                        withGuide = withGuide,
                        catchUp = catchUp,
                        watched = watched,
                    ),
            )
        }
    }

    /** The stored form: names rather than objects, so a renamed class never loses what was chosen. */
    @Serializable
    private data class Stored(
        val chips: Map<Int, String> = emptyMap(),
        val topic: String? = null,
        val country: String? = null,
        val language: String? = null,
        val languageTags: List<String> = emptyList(),
        val quality: String? = null,
        val onNow: String? = null,
        val withGuide: Boolean = false,
        val catchUp: Boolean = false,
        val watched: Boolean = false,
    )
}
