package app.tuned.ui.details

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.data.Metadata
import app.tuned.data.db.ItemEntity
import app.tuned.ui.common.BackTopBar
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** The TMDB details for one film or show: loading, found, or nothing to show. */
class DetailsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container
    val item = MutableStateFlow<ItemEntity?>(null)
    val details = MutableStateFlow<Metadata?>(null)

    /** False until a key is set in Settings, so the screen can say where ratings come from. */
    val hasKey = MutableStateFlow(true)
    private var loaded: String? = null

    fun loadItem(id: Long) {
        viewModelScope.launch {
            val found = container.db.items().get(id) ?: return@launch
            item.value = found
            load(found.name, tv = false)
        }
    }

    fun load(
        name: String,
        tv: Boolean,
    ) {
        if (loaded == name) return
        loaded = name
        viewModelScope.launch {
            hasKey.value = container.metadata.hasKey()
            details.value = container.metadata.lookup(name, tv)
        }
    }
}

/** A film's page, from a long press on its poster: rating, summary, trailer and Play. */
@Composable
fun MovieScreen(
    itemId: Long,
    onPlay: (Long) -> Unit,
    onBack: () -> Unit,
    vm: DetailsViewModel = viewModel(),
) {
    LaunchedEffect(itemId) { vm.loadItem(itemId) }
    val item by vm.item.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(details?.title ?: item?.name.orEmpty(), onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row {
                AsyncImage(
                    model = details?.poster ?: item?.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onPlay(itemId) }) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.watch_now))
                    }
                    details?.trailer?.let { TrailerButton(it) }
                }
            }
            Spacer(Modifier.padding(top = 16.dp))
            DetailsText(details, hasKey)
        }
    }
}

/** Year, rating and summary; with no key, one line on where to add it. */
@Composable
fun DetailsText(
    details: Metadata?,
    hasKey: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            details.rating?.let { rating ->
                Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.details_rating, String.format(Locale.getDefault(), "%.1f", rating)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(16.dp))
            }
            details.year?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        }
        details.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            stringResource(R.string.details_source),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Opens the trailer in YouTube (or the browser). */
@Composable
fun TrailerButton(videoId: String) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$videoId".toUri()))
        } catch (_: ActivityNotFoundException) {
            // Nothing on the device can open a link.
        }
    }) {
        Icon(Icons.Filled.Movie, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.details_trailer))
    }
}
