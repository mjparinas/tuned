package com.tunedplayer.app.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.PosterArt
import com.tunedplayer.app.core.Titles
import com.tunedplayer.app.data.Metadata
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.ShowSummary
import com.tunedplayer.app.diagnostics.attempt
import java.util.Locale

/**
 * Movies and Shows on a TV use the big screen the same way Live TV does: beside the poster grid, what the title under
 * the remote's focus is, from the metadata already on the device. It is words and a poster only, never a stream, and
 * it takes no focus, so the arrows stay in the grid (docs/decisions/tv.md).
 */
@Composable
internal fun PosterInfoPane(
    focus: PosterFocus?,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(
            Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (focus) {
                null -> {
                    Text(
                        stringResource(R.string.tv_browse_pick),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is PosterFocus.Movie -> {
                    MovieDetails(focus.item)
                }

                is PosterFocus.Show -> {
                    ShowDetails(focus.show)
                }
            }
        }
    }
}

/** The film or show the remote is on, for [PosterInfoPane]. */
sealed interface PosterFocus {
    data class Movie(
        val item: ItemEntity,
    ) : PosterFocus

    data class Show(
        val show: ShowSummary,
    ) : PosterFocus
}

@Composable
private fun MovieDetails(item: ItemEntity) {
    val meta by rememberCachedMetadata(item.name, tv = false)
    val image = meta?.poster?.takeIf { it.isNotBlank() } ?: item.logo
    PosterHeader(image, Titles.display(item.name))
    MetaLines(
        year = meta?.year ?: item.year?.toString(),
        rating = meta?.rating ?: item.topRating,
        runtime = meta?.runtime,
        genres = meta?.genres.orEmpty(),
    )
    Overview(meta?.overview)
}

@Composable
private fun ShowDetails(show: ShowSummary) {
    val meta by rememberCachedMetadata(show.showName, tv = true)
    val image = PosterArt.showCover(meta?.poster, show.logo)
    PosterHeader(image, Titles.display(show.showName))
    Text(
        if (show.newEpisodes > 0) {
            stringResource(
                R.string.episodes_with_new,
                pluralStringResource(R.plurals.episodes, show.episodes, show.episodes),
                pluralStringResource(R.plurals.new_episodes, show.newEpisodes, show.newEpisodes),
            )
        } else {
            pluralStringResource(R.plurals.episodes, show.episodes, show.episodes)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    MetaLines(year = meta?.year, rating = meta?.rating, runtime = meta?.runtime, genres = meta?.genres.orEmpty())
    Overview(meta?.overview)
}

@Composable
private fun PosterHeader(
    image: String?,
    title: String,
) {
    AsyncImage(
        model = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)),
    )
    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun MetaLines(
    year: String?,
    rating: Double?,
    runtime: String?,
    genres: List<String>,
) {
    val bits =
        buildList {
            year?.takeIf { it.isNotBlank() }?.let(::add)
            rating?.let { add(stringResource(R.string.details_rating, String.format(Locale.US, "%.1f", it))) }
            runtime?.takeIf { it.isNotBlank() }?.let(::add)
        }
    if (bits.isNotEmpty()) {
        Text(
            bits.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    genres.takeIf { it.isNotEmpty() }?.let {
        Text(
            it.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Overview(overview: String?) {
    overview?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Metadata already on the device for [name]; never asks the network, so flipping through posters stays quiet. */
@Composable
private fun rememberCachedMetadata(
    name: String,
    tv: Boolean,
): State<Metadata?> {
    val metadata = LocalContext.current.container.metadata
    return produceState<Metadata?>(null, name, tv) {
        value = attempt(TAG, "cached poster info") { metadata.cached(name, tv) }.getOrNull()
    }
}

private const val TAG = "PosterInfoPane"
