package com.tunedplayer.app.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.PosterArt
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.skeleton

/**
 * Crops a real poster to fill the tile. A channel logo is shown whole, centred and never blown up past the tile, so a
 * small square logo stays sharp; a show's wide picture is shown whole across the tile, over its blurred backdrop
 * ([PosterBackdrop]), so title text on it is never zoomed or cut off ([PosterArt.fit]).
 */
private class PosterScale(
    private val show: Boolean,
) : ContentScale {
    override fun computeScaleFactor(
        srcSize: Size,
        dstSize: Size,
    ): ScaleFactor = when (PosterArt.fit(srcSize.width, srcSize.height, show)) {
        PosterArt.Fit.CROP -> {
            ContentScale.Crop.computeScaleFactor(srcSize, dstSize)
        }

        PosterArt.Fit.WHOLE_ON_BACKDROP -> {
            ContentScale.Fit.computeScaleFactor(srcSize, dstSize)
        }

        PosterArt.Fit.LOGO -> {
            val fit = ContentScale.Fit.computeScaleFactor(srcSize, dstSize).scaleX * PosterArt.LOGO_FILL
            ScaleFactor(fit, fit)
        }
    }
}

private val FilmScale = PosterScale(show = false)
private val ShowScale = PosterScale(show = true)

/**
 * A show's wide picture again, cropped to fill the tall tile behind the whole one, blurred and dimmed so it reads as
 * a backdrop. The blur needs Android 12; older devices get the dimmed crop alone. Coil's memory cache serves it, so it
 * costs no second download.
 */
@Composable
private fun PosterBackdrop(
    image: String?,
    modifier: Modifier,
) {
    AsyncImage(
        model = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = BACKDROP_ALPHA,
        modifier = modifier.blur(BACKDROP_BLUR),
    )
}

private const val BACKDROP_ALPHA = 0.45f
private val BACKDROP_BLUR = 16.dp

/** A show card's picture and rating, from one TMDB lookup ([rememberShowArt]). */
@Immutable
data class ShowArt(
    /** False until the device's cache has been read, so the card doesn't flash the provider's still first. */
    val ready: Boolean,
    val image: String?,
    val rating: Double?,
)

/**
 * The show's cover for its card: TMDB's poster from the device's cache at once, then from TMDB only when the device
 * has none, falling back to the provider's cover ([PosterArt.showCover]). It never touches the provider, so it never
 * takes the one stream connection.
 */
@Composable
fun rememberShowArt(
    name: String,
    providerCover: String?,
): ShowArt {
    val metadata = LocalContext.current.container.metadata
    val art by produceState(ShowArt(ready = false, image = null, rating = null), name, providerCover) {
        val cached = attempt(TAG, "cached show art") { metadata.cached(name, tv = true) }.getOrNull()
        value = ShowArt(ready = true, image = PosterArt.showCover(cached?.poster, providerCover), rating = cached?.rating)
        val fresh = attempt(TAG, "show art") { metadata.summary(name, tv = true) }.getOrNull() ?: return@produceState
        value = ShowArt(ready = true, image = PosterArt.showCover(fresh.poster ?: cached?.poster, providerCover), rating = fresh.rating)
    }
    return art
}

private const val TAG = "Posters"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Poster(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    favourite: Boolean = false,
    onToggleFavourite: (() -> Unit)? = null,
    onPlayNow: (() -> Unit)? = null,
    rating: Double? = null,
    watched: Boolean = false,
    /** More long-press menu entries: label and action. */
    extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
    /** A show's card: a wide picture shows whole over its blurred backdrop, never cropped ([PosterArt.fit]). */
    show: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val hasMenu = onToggleFavourite != null || onPlayNow != null || extraMenu.isNotEmpty()
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .focusRing(RoundedCornerShape(10.dp), MaterialTheme.colorScheme.primary)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = if (hasMenu) stringResource(R.string.a11y_more_options) else null,
                onLongClick = if (hasMenu) ({ menu = true }) else null,
            ),
    ) {
        Box {
            val tile =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
            // Known once the picture loads: a show's wide picture gets its backdrop behind it.
            var backdrop by remember(image, show) { mutableStateOf(false) }
            Box(tile.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                if (backdrop) PosterBackdrop(image, Modifier.matchParentSize())
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = if (show) ShowScale else FilmScale,
                    onSuccess = { loaded ->
                        val size = loaded.painter.intrinsicSize
                        backdrop = PosterArt.fit(size.width, size.height, show) == PosterArt.Fit.WHOLE_ON_BACKDROP
                    },
                    modifier = Modifier.matchParentSize(),
                )
            }
            rating?.let { RatingBadge(it, Modifier.align(Alignment.BottomEnd).padding(6.dp)) }
            if (favourite) {
                Icon(
                    Icons.Filled.Favorite,
                    stringResource(R.string.a11y_favourite),
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (watched) {
                Icon(
                    Icons.Filled.CheckCircle,
                    stringResource(R.string.watched),
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(20.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    tint = Color.White,
                )
            }
            if (hasMenu) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    onPlayNow?.let { play ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.play_now)) },
                            onClick = {
                                menu = false
                                play()
                            },
                        )
                    }
                    onToggleFavourite?.let { toggle ->
                        DropdownMenuItem(
                            text = { Text(stringResource(if (favourite) R.string.favourite_remove else R.string.favourite_add)) },
                            onClick = {
                                menu = false
                                toggle()
                            },
                        )
                    }
                    extraMenu.forEach { (label, action) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menu = false
                                action()
                            },
                        )
                    }
                }
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
internal fun PosterPlaceholder() {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(10.dp))
            .skeleton(),
    )
}

@Composable
fun Logo(
    url: String?,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
    )
}
