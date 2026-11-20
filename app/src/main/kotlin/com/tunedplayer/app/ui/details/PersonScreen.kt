package com.tunedplayer.app.ui.details

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.Filmography
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.data.Person
import com.tunedplayer.app.data.hiddenKeys
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.common.BackTopBar
import com.tunedplayer.app.ui.common.focusRing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Where a credit is in the playlist, to open it from the actor's page. */
sealed interface InPlaylist {
    data class Movie(
        val itemId: Long,
    ) : InPlaylist

    data class Show(
        val showKey: String,
        val title: String,
    ) : InPlaylist
}

/** An actor's page: their films and shows from the device at once, then TMDB's latest, then which ones can be played. */
class PersonViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container
    val person = MutableStateFlow<Person?>(null)
    val loading = MutableStateFlow(true)
    val hasKey = MutableStateFlow(true)

    /** By [key], for the credits found in the playlist; filled in as the list is checked. */
    val playable = MutableStateFlow<Map<String, InPlaylist>>(emptyMap())
    private var loaded: Pair<Long, String>? = null

    fun load(
        id: Long,
        name: String,
    ) {
        if (loaded == id to name) return
        loaded = id to name
        viewModelScope.launchSafely("Person", onError = { loading.value = false }) {
            val people = container.people
            hasKey.value = container.metadata.hasKey()
            // Cast saved before TMDB ids were kept has none: look the name up once.
            val personId = id.takeIf { it > 0 } ?: people.idFor(name)
            if (personId != null) {
                people.cached(personId)?.let {
                    person.value = it
                    match(it)
                }
                people.load(personId)?.let { fresh ->
                    if (fresh != person.value) {
                        person.value = fresh
                        match(fresh)
                    }
                }
            }
            loading.value = false
        }
    }

    /**
     * Finds each credit in the playlist with the same full-text search as Search, then keeps only exact title (and
     * year) matches. One credit at a time, published in batches, so a long career fills in as it goes.
     */
    private suspend fun match(person: Person) {
        val s = container.settings.settings.first()
        val hidden = s.hiddenKeys()
        val adult = s.showAdult
        val items = container.db.items()
        val found = playable.value.toMutableMap()
        person.credits.forEachIndexed { index, credit ->
            val match = Search.match(credit.title)
            if (match.isNotEmpty()) {
                val hit =
                    if (credit.tv) {
                        items
                            .searchShows(match, hidden, adult, LIMIT)
                            .firstOrNull { Filmography.matches(it.showName, credit) }
                            ?.let { InPlaylist.Show(it.showKey, it.showName) }
                    } else {
                        items
                            .searchMovies(match, hidden, adult, LIMIT)
                            .firstOrNull { Filmography.matches(it.name, credit) }
                            ?.let { InPlaylist.Movie(it.id) }
                    }
                if (hit != null) found[key(credit)] = hit
            }
            if (index % BATCH == BATCH - 1) playable.value = found.toMap()
        }
        playable.value = found.toMap()
    }

    companion object {
        private const val LIMIT = 50
        private const val BATCH = 20

        fun key(credit: Filmography.Credit) = "${if (credit.tv) "tv" else "movie"}:${credit.tmdbId}"
    }
}

/**
 * Opened by tapping someone in a cast: their photo and biography, then every film and show they were in, oldest
 * first. The ones in the playlist are marked and open their page; the rest are shown dimmed, for reference.
 */
@Composable
fun PersonScreen(
    personId: Long,
    name: String,
    onOpenMovie: (Long) -> Unit,
    onOpenShow: (String, String) -> Unit,
    onBack: () -> Unit,
    vm: PersonViewModel = viewModel(),
) {
    LaunchedEffect(personId, name) { vm.load(personId, name) }
    val person by vm.person.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val playable by vm.playable.collectAsStateWithLifecycle()
    var onlyPlayable by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar(person?.name ?: name, onBack) }) { padding ->
        val current = person
        if (current == null) {
            Box(Modifier.padding(padding).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator()
                    !hasKey -> Text(stringResource(R.string.details_no_key), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text(stringResource(R.string.person_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }
        val credits = if (onlyPlayable) current.credits.filter { PersonViewModel.key(it) in playable } else current.credits
        // A LazyColumn draws only the rows on screen, and each poster loads as its row appears, so a career of
        // hundreds of credits opens as fast as a short one.
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") { Header(current) }
            item(key = "filter") {
                FilterChip(
                    selected = onlyPlayable,
                    onClick = { onlyPlayable = !onlyPlayable },
                    label = { Text(pluralStringResource(R.plurals.person_in_playlist, playable.size, playable.size)) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(credits, key = { PersonViewModel.key(it) }) { credit ->
                val target = playable[PersonViewModel.key(credit)]
                CreditRow(credit, target) {
                    when (target) {
                        is InPlaylist.Movie -> onOpenMovie(target.itemId)
                        is InPlaylist.Show -> onOpenShow(target.showKey, target.title)
                        null -> Unit
                    }
                }
            }
            if (credits.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(if (onlyPlayable) R.string.person_none_in_playlist else R.string.person_no_credits),
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "source") {
                Text(
                    stringResource(R.string.details_source),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Header(person: Person) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AsyncImage(
            model = person.photo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        person.biography?.let { bio ->
            Text(
                bio,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else BIO_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { expanded = !expanded },
            )
        }
    }
}

@Composable
private fun CreditRow(
    credit: Filmography.Credit,
    target: InPlaylist?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val playable = target != null
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .focusRing(shape, MaterialTheme.colorScheme.primary)
            .clip(shape)
            .clickable(enabled = playable, onClick = onClick)
            .padding(8.dp)
            .alpha(if (playable) 1f else DIMMED),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = credit.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .width(52.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                credit.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val kind = stringResource(if (credit.tv) R.string.person_show else R.string.person_film)
            val episodes = credit.episodes?.let { pluralStringResource(R.plurals.person_episodes, it, it) }
            Text(
                listOfNotNull(credit.year ?: stringResource(R.string.person_upcoming), kind, episodes).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            credit.character?.let {
                Text(
                    stringResource(R.string.person_as, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (playable) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = stringResource(R.string.person_in_your_playlist),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp).size(28.dp),
            )
        }
    }
}

private const val BIO_LINES = 4
private const val DIMMED = 0.55f
