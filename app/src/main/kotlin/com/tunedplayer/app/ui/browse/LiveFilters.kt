package com.tunedplayer.app.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.tunedplayer.app.R
import com.tunedplayer.app.core.ChannelQuality
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.LiveTopic
import com.tunedplayer.app.core.ProgrammeGenre
import com.tunedplayer.app.core.RegionLanguages
import com.tunedplayer.app.core.Regions
import com.tunedplayer.app.ui.settings.PinDialog
import com.tunedplayer.app.ui.settings.PinMode
import com.tunedplayer.app.ui.settings.languageName

/** Where the channels come from: one country, or every country in the playlist that speaks a language ([RegionLanguages]). */
sealed interface LivePlace {
    val tags: List<String>

    data class Country(
        val tag: String,
    ) : LivePlace {
        override val tags: List<String> get() = listOf(tag)
    }

    data class Language(
        val code: String,
        override val tags: List<String>,
    ) : LivePlace
}

/**
 * Whether the Adult quick filter is offered: not on a kids profile or a playlist with no adult channels; otherwise
 * straight away while adult categories are unlocked, or after the parental PIN (or setting one, as Settings does).
 */
enum class AdultGate { Hidden, Locked, NoPin, Unlocked }

/**
 * Live TV's quick filters, narrowing together: at most one kind of channel, one picture quality, one kind of programme
 * on now and one country or language, and any of Has guide, Catch-up and Recently watched.
 */
data class LiveQuick(
    val topic: LiveTopic? = null,
    val place: LivePlace? = null,
    val quality: ChannelQuality? = null,
    val onNow: ProgrammeGenre? = null,
    val withGuide: Boolean = false,
    val catchUp: Boolean = false,
    val watched: Boolean = false,
) {
    val isEmpty: Boolean get() = this == LiveQuick()

    /** The full-text query [com.tunedplayer.app.data.db.LIVE_QUICK] reads for the topic, or '' for any channel. */
    val topicQuery: String get() = topic?.fullText.orEmpty()

    /** The full-text query [com.tunedplayer.app.data.db.LIVE_QUICK] reads for the quality, or '' for any. */
    val qualityQuery: String get() = quality?.fullText.orEmpty()

    /** The kind of programme on now that [com.tunedplayer.app.data.db.LIVE_QUICK] keeps, or 0 for any. */
    val genreCode: Int get() = onNow?.code ?: 0

    /**
     * The keys [com.tunedplayer.app.data.db.LIVE_QUICK] reads: the [groups] (Live TV's categories) that match the topic, then the
     * country tags, or "any" for every country, then "adult" for the Adult filter and "guide", "catchup" and "watched"
     * for those switches. At most [MAX_GROUPS] categories, so the query stays under SQLite's limit of 999 values on
     * older phones.
     */
    fun keys(groups: List<String>): List<String> {
        val topicGroups = topic?.let { t -> groups.filter { t.matches(it) }.take(MAX_GROUPS).map { "g:$it" } }.orEmpty()
        val flags =
            listOfNotNull(
                ADULT.takeIf { topic?.isAdult == true },
                GUIDE.takeIf { withGuide },
                CATCH_UP.takeIf { catchUp },
                WATCHED.takeIf { watched },
            )
        return topicGroups + (place?.tags ?: listOf(ANY)) + flags
    }

    companion object {
        const val ANY = "any"
        const val ADULT = "adult"
        const val GUIDE = "guide"
        const val CATCH_UP = "catchup"
        const val WATCHED = "watched"
        private const val MAX_GROUPS = 790
    }
}

/** What one Live TV list reads for its quick filters, so the list reloads only when one of them changes. */
internal data class LiveQuickArgs(
    val topic: String,
    val keys: List<String>,
    val quality: String,
    val genre: Int,
)

/** Which of the newer filters would find anything, so the sheet offers only those (a chosen one always shows). */
data class LiveOffers(
    val qualities: Set<ChannelQuality> = emptySet(),
    val onNow: Set<ProgrammeGenre> = emptySet(),
    val guide: Boolean = false,
    val catchUp: Boolean = false,
    val watched: Boolean = false,
)

/**
 * The Filters chip in Live TV's row of categories. It names what is chosen ("Sports · Canada"), opens the sheet to
 * choose, and has a cross to clear, which is also an action on the chip for a screen reader.
 */
@Composable
internal fun LiveQuickChip(vm: BrowseViewModel) {
    val quick by vm.liveQuick.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val clearLabel = stringResource(R.string.live_filters_clear)
    val clear = { vm.liveQuick.value = LiveQuick() }
    FilterChip(
        selected = !quick.isEmpty,
        onClick = { open = true },
        leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = { Text(quickLabel(quick) ?: stringResource(R.string.live_filters)) },
        modifier =
            if (quick.isEmpty) {
                Modifier
            } else {
                Modifier.semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(clearLabel) {
                                clear()
                                true
                            },
                        )
                }
            },
        trailingIcon =
            if (quick.isEmpty) {
                null
            } else {
                { Icon(Icons.Filled.Close, contentDescription = clearLabel, modifier = Modifier.size(18.dp).clickable(onClick = clear)) }
            },
    )
    if (open) LiveFiltersSheet(vm, quick) { open = false }
}

/** What the Filters chip says: the first two filters chosen, then how many more ("Sports · 4K +2"). */
@Composable
private fun quickLabel(quick: LiveQuick): String? {
    val parts =
        listOfNotNull(
            quick.topic?.let { stringResource(topicLabel(it)) },
            quick.quality?.let { stringResource(qualityLabel(it)) },
            quick.onNow?.let { stringResource(R.string.live_filter_on_now_label, stringResource(genreLabel(it))) },
            if (quick.withGuide) stringResource(R.string.live_filter_guide) else null,
            if (quick.catchUp) stringResource(R.string.live_filter_catch_up) else null,
            if (quick.watched) stringResource(R.string.live_filter_watched) else null,
            quick.place?.let { placeName(it) },
        )
    if (parts.isEmpty()) return null
    val shown = parts.take(2).joinToString(" · ")
    return if (parts.size > 2) stringResource(R.string.live_filters_more, shown, parts.size - 2) else shown
}

private fun qualityLabel(quality: ChannelQuality): Int = when (quality) {
    ChannelQuality.UHD -> R.string.live_filter_uhd
    ChannelQuality.FHD -> R.string.live_filter_fhd
    ChannelQuality.HD -> R.string.live_filter_hd
    ChannelQuality.SD -> R.string.live_filter_sd
}

private fun genreLabel(genre: ProgrammeGenre): Int = when (genre) {
    ProgrammeGenre.SPORTS -> R.string.live_filter_on_now_sports
    ProgrammeGenre.MOVIES -> R.string.live_filter_on_now_movies
    ProgrammeGenre.NEWS -> R.string.live_filter_on_now_news
    ProgrammeGenre.KIDS -> R.string.live_filter_on_now_kids
    ProgrammeGenre.DOCUMENTARY -> R.string.live_filter_on_now_documentary
}

private fun topicLabel(topic: LiveTopic): Int = when (topic) {
    LiveTopic.SPORTS -> R.string.live_filter_sports
    LiveTopic.PPV -> R.string.live_filter_ppv
    LiveTopic.NEWS -> R.string.live_filter_news
    LiveTopic.KIDS -> R.string.live_filter_kids
    LiveTopic.MOVIES -> R.string.live_filter_movies
    LiveTopic.MUSIC -> R.string.live_filter_music
    LiveTopic.DOCUMENTARY -> R.string.live_filter_documentary
    LiveTopic.ADULT -> R.string.live_filter_adult
}

@Composable
private fun placeName(place: LivePlace): String = when (place) {
    is LivePlace.Country -> Regions.displayName(place.tag)
    is LivePlace.Language -> languageName(place.code)
}

/**
 * Chooses the quick filters. Each tap applies at once, so the list behind updates while the sheet is open; a second
 * tap on a chosen chip takes it off. Languages and countries show only when the playlist has more than one.
 */
@Composable
private fun LiveFiltersSheet(
    vm: BrowseViewModel,
    quick: LiveQuick,
    onDismiss: () -> Unit,
) {
    val regions by vm.liveRegions.collectAsStateWithLifecycle()
    val languages = remember(regions) { RegionLanguages.group(regions.map { it.region }) }
    val countries = remember(regions) { Regions.sortedByName(regions) { it.region } }
    val set = { q: LiveQuick -> vm.liveQuick.value = q }
    // Read once as the sheet opens; until then (a moment) only what is already chosen shows.
    val offers by produceState(LiveOffers()) { value = vm.liveOffers() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.live_filters_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    if (!quick.isEmpty) TextButton(onClick = { set(LiveQuick()) }) { Text(stringResource(R.string.live_filters_clear)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.live_filters_done)) }
                }
            }
            item(key = "topics") {
                Section(stringResource(R.string.live_filters_type)) {
                    LiveTopic.entries.filterNot { it.isAdult }.forEach { t ->
                        val on = quick.topic == t
                        FilterChip(
                            selected = on,
                            onClick = { set(quick.copy(topic = if (on) null else t)) },
                            label = { Text(stringResource(topicLabel(t))) },
                        )
                    }
                    AdultChip(vm, quick, set)
                }
            }
            val qualities = ChannelQuality.entries.filter { it in offers.qualities || it == quick.quality }
            if (qualities.isNotEmpty()) {
                item(key = "quality") {
                    Section(stringResource(R.string.live_filters_quality)) {
                        qualities.forEach { q ->
                            val on = quick.quality == q
                            FilterChip(
                                selected = on,
                                onClick = { set(quick.copy(quality = if (on) null else q)) },
                                label = { Text(stringResource(qualityLabel(q))) },
                            )
                        }
                    }
                }
            }
            val genres = ProgrammeGenre.entries.filter { it in offers.onNow || it == quick.onNow }
            if (genres.isNotEmpty()) {
                item(key = "on-now") {
                    Section(stringResource(R.string.live_filters_on_now)) {
                        genres.forEach { g ->
                            val on = quick.onNow == g
                            FilterChip(
                                selected = on,
                                onClick = { set(quick.copy(onNow = if (on) null else g)) },
                                label = { Text(stringResource(genreLabel(g))) },
                            )
                        }
                    }
                }
            }
            val guide = offers.guide || quick.withGuide
            val catchUp = offers.catchUp || quick.catchUp
            val watched = offers.watched || quick.watched
            if (guide || catchUp || watched) {
                item(key = "only") {
                    Section(stringResource(R.string.live_filters_only)) {
                        if (guide) {
                            FilterChip(
                                selected = quick.withGuide,
                                onClick = { set(quick.copy(withGuide = !quick.withGuide)) },
                                label = { Text(stringResource(R.string.live_filter_guide)) },
                            )
                        }
                        if (catchUp) {
                            FilterChip(
                                selected = quick.catchUp,
                                onClick = { set(quick.copy(catchUp = !quick.catchUp)) },
                                label = { Text(stringResource(R.string.live_filter_catch_up)) },
                            )
                        }
                        if (watched) {
                            FilterChip(
                                selected = quick.watched,
                                onClick = { set(quick.copy(watched = !quick.watched)) },
                                label = { Text(stringResource(R.string.live_filter_watched)) },
                            )
                        }
                    }
                }
            }
            if (languages.size > 1) {
                item(key = "languages") {
                    Section(stringResource(R.string.live_filters_language)) {
                        languages.forEach { (code, tags) ->
                            val on = (quick.place as? LivePlace.Language)?.code == code
                            FilterChip(
                                selected = on,
                                onClick = { set(quick.copy(place = if (on) null else LivePlace.Language(code, tags))) },
                                label = { Text(languageName(code)) },
                            )
                        }
                    }
                }
            }
            if (countries.size > 1) {
                item(key = "countries") {
                    Section(stringResource(R.string.live_filters_country)) {
                        countries.forEach { r ->
                            val place = LivePlace.Country(r.region)
                            val on = quick.place == place
                            FilterChip(
                                selected = on,
                                onClick = { set(quick.copy(place = if (on) null else place)) },
                                label = { Text(Regions.displayName(r.region)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Adult filter, behind the parental PIN (Melvin, 2026-11-19: "adult (behind pin)"). While adult categories are
 * locked it shows a lock, and a tap asks for the PIN (or to set one) before choosing it; the PIN unlocks adult
 * categories everywhere, exactly as the switch in Settings does, and only until the app closes.
 */
@Composable
private fun AdultChip(
    vm: BrowseViewModel,
    quick: LiveQuick,
    set: (LiveQuick) -> Unit,
) {
    val gate by vm.liveAdult.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf<PinMode?>(null) }
    if (gate == AdultGate.Hidden) return
    val on = quick.topic == LiveTopic.ADULT
    when (gate) {
        AdultGate.Hidden -> {}

        AdultGate.Unlocked -> {
            FilterChip(
                selected = on,
                onClick = { set(quick.copy(topic = if (on) null else LiveTopic.ADULT)) },
                label = { Text(stringResource(R.string.live_filter_adult)) },
            )
        }

        AdultGate.Locked, AdultGate.NoPin -> {
            FilterChip(
                selected = false,
                onClick = { pin = if (gate == AdultGate.Locked) PinMode.Unlock else PinMode.Create },
                label = { Text(stringResource(R.string.live_filter_adult)) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.live_filter_adult_locked),
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
    pin?.let { mode ->
        PinDialog(mode, vm.adultPin, onDone = { pin = null }, onSuccess = { set(quick.copy(topic = LiveTopic.ADULT)) })
    }
}

@Composable
private fun Section(
    title: String,
    chips: @Composable () -> Unit,
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

/**
 * Quick filters that leave Live TV empty aren't a dead end: the list says so and offers to clear them. A search that
 * finds nothing is [NoMatches]'s, so this shows only without one.
 */
@Composable
internal fun NoQuickMatches(
    items: LazyPagingItems<*>,
    vm: BrowseViewModel,
) {
    val quick by vm.liveQuick.collectAsStateWithLifecycle()
    val search by vm.search(ContentKind.LIVE).collectAsStateWithLifecycle()
    val empty = items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading
    if (!empty || quick.isEmpty || search.isNotBlank()) return
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.live_filters_none),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = { vm.liveQuick.value = LiveQuick() }) { Text(stringResource(R.string.live_filters_clear)) }
    }
}
