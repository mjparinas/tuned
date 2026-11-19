package app.tuned.ui.details

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.Titles
import app.tuned.data.CastMember
import app.tuned.data.Metadata
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.launchSafely
import app.tuned.diagnostics.startActivitySafely
import app.tuned.ui.common.focusRing
import app.tuned.ui.common.rememberStartFocus
import app.tuned.ui.common.startFocus
import app.tuned.ui.library.LibraryViewModel
import app.tuned.ui.library.WatchLaterButton
import app.tuned.ui.library.WatchedButton
import app.tuned.ui.offline.DownloadButton
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow

/** The TMDB details for one film or show: what the device has at once, then anything newer. */
class DetailsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container
    val item = MutableStateFlow<ItemEntity?>(null)
    val details = MutableStateFlow<Metadata?>(null)

    /** Where a started film was left, for "Resume" instead of "Play". */
    val resumable = MutableStateFlow(false)

    /** False until a key is set in Settings, so the page can say where ratings come from. */
    val hasKey = MutableStateFlow(true)
    private var loaded: String? = null

    fun loadItem(id: Long) {
        viewModelScope.launchSafely("Details") {
            val found = container.db.items().get(id) ?: return@launchSafely
            item.value = found
            val h = container.db.library().progress(id)
            resumable.value = h != null && h.positionMs > RESUME_FROM_MS && (h.durationMs <= 0 || h.positionMs < h.durationMs * DONE)
            load(found.name, tv = false)
        }
    }

    fun load(
        name: String,
        tv: Boolean,
    ) {
        if (loaded == name) return
        loaded = name
        viewModelScope.launchSafely("Details") {
            details.value = container.metadata.cached(name, tv)
            hasKey.value = container.metadata.hasKey()
            container.metadata.details(name, tv)?.let { details.value = it }
        }
    }

    private companion object {
        const val RESUME_FROM_MS = 30_000L
        const val DONE = 0.95
    }
}

/**
 * A film's page, opened by tapping its poster: the backdrop across the top, poster and title over its lower edge,
 * then the rating, genres, Play and Trailer, the summary and the cast. Everything comes from the device once seen.
 */
@Composable
fun MovieScreen(
    itemId: Long,
    onPlay: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenPerson: (CastMember) -> Unit = {},
    vm: DetailsViewModel = viewModel(),
) {
    val start = rememberStartFocus()

    LaunchedEffect(itemId) { vm.loadItem(itemId) }
    val item by vm.item.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val resumable by vm.resumable.collectAsStateWithLifecycle()
    // A Surface, not just a background colour: it also sets the text and icon colour for the theme. Without it the
    // title and the icon buttons fell back to black, unreadable in dark mode.
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            Backdrop(details?.backdrop ?: details?.poster ?: item?.logo, onBack)
            Row(Modifier.padding(horizontal = 20.dp).offset(y = (-72).dp)) {
                AsyncImage(
                    model = details?.poster ?: item?.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .width(112.dp)
                            .aspectRatio(2f / 3f)
                            .shadow(12.dp, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.padding(top = 56.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        details?.title ?: Titles.display(item?.name.orEmpty()),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MetaLine(details)
                }
            }
            Column(
                Modifier.padding(horizontal = 20.dp).offset(y = (-56).dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Every action on the film in sight, wrapping to a second line on a narrow phone rather than squeezing.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    // On a TV the remote starts on Play, so OK plays (or resumes) straight away.
                    Button(
                        onClick = { onPlay(itemId) },
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
                        modifier = Modifier.startFocus(start),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (resumable) R.string.details_resume else R.string.details_play))
                    }
                    details?.trailer?.let { TrailerButton(it) }
                    item?.let {
                        DownloadButton(it)
                        WatchLaterButton(LibraryViewModel.movie(it.id, it.name, it.logo))
                        WatchedButton(it.id)
                        FilmPicks(it.id)
                    }
                }
                DetailsBody(details, hasKey, onOpenPerson = onOpenPerson)
            }
        }
    }
}

/** The wide picture at the top, fading into the page, with Back over it. */
@Composable
private fun Backdrop(
    image: String?,
    onBack: () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
        AsyncImage(
            model = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.35f to Color.Transparent, 1f to background)),
        )
        IconButton(
            onClick = onBack,
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f), contentColor = Color.White),
            modifier = Modifier.statusBarsPadding().padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
    }
}

/** Year, runtime and rating on one line. */
@Composable
fun MetaLine(details: Metadata?) {
    if (details == null) return
    val locale = LocalConfiguration.current.locales[0]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        details.rating?.let { RatingChip(String.format(locale, "%.1f", it)) }
        listOfNotNull(details.year, details.runtime).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RatingChip(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.details_rating, text),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * Genres, summary, cast and the TMDB credit; in a build without a TMDB key, one line saying so. Tapping someone in the cast
 * opens their films and shows.
 */
@Composable
fun DetailsBody(
    details: Metadata?,
    hasKey: Boolean,
    modifier: Modifier = Modifier,
    onOpenPerson: (CastMember) -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (details == null) {
            if (!hasKey) {
                Text(
                    stringResource(R.string.details_no_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        if (details.genres.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                details.genres.forEach { genre ->
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(genre, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        details.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (details.cast.isNotEmpty()) {
            Text(stringResource(R.string.details_cast), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            CastRow(details.cast, onOpenPerson)
        }
        Text(
            stringResource(R.string.details_source),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CastRow(
    cast: List<CastMember>,
    onOpen: (CastMember) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(cast, key = { it.name + it.character }) { person ->
            Column(
                Modifier
                    .width(84.dp)
                    .focusRing(shape, MaterialTheme.colorScheme.primary)
                    .clip(shape)
                    .clickable(onClickLabel = stringResource(R.string.person_open, person.name)) { onOpen(person) }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = person.photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(68.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                Spacer(Modifier.height(6.dp))
                Text(person.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                person.character?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Opens the trailer in YouTube (or the browser). */
@Composable
fun TrailerButton(videoId: String) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            context.startActivitySafely(Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$videoId".toUri()), "Trailer")
        },
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(Icons.Filled.Movie, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.details_trailer))
    }
}
