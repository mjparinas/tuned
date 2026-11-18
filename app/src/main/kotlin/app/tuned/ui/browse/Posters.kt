package app.tuned.ui.browse

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tuned.R
import app.tuned.core.PosterArt
import app.tuned.ui.common.focusRing
import app.tuned.ui.common.skeleton
import coil3.compose.AsyncImage

/**
 * Crops a real poster to fill the tile, but shows a channel logo whole, centred and never blown up past the tile, so a
 * small square logo stays sharp instead of being cropped and enlarged ([PosterArt]).
 */
private object PosterScale : ContentScale {
    override fun computeScaleFactor(
        srcSize: Size,
        dstSize: Size,
    ): ScaleFactor = if (PosterArt.isPoster(srcSize.width, srcSize.height)) {
        ContentScale.Crop.computeScaleFactor(srcSize, dstSize)
    } else {
        val fit = ContentScale.Fit.computeScaleFactor(srcSize, dstSize).scaleX * PosterArt.LOGO_FILL
        ScaleFactor(fit, fit)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Poster(
    title: String,
    image: String?,
    onClick: () -> Unit,
    subtitle: String? = null,
    favourite: Boolean = false,
    onToggleFavourite: (() -> Unit)? = null,
    onPlayNow: (() -> Unit)? = null,
    rating: Double? = null,
    watched: Boolean = false,
    /** More long-press menu entries: label and action. */
    extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var menu by remember { mutableStateOf(false) }
    val hasMenu = onToggleFavourite != null || onPlayNow != null || extraMenu.isNotEmpty()
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .focusRing(RoundedCornerShape(10.dp), MaterialTheme.colorScheme.primary)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = if (hasMenu) stringResource(R.string.a11y_more_options) else null,
                onLongClick = if (hasMenu) ({ menu = true }) else null,
            ),
    ) {
        Box {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = PosterScale,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
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
