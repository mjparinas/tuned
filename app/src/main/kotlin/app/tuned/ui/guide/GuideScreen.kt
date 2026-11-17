package app.tuned.ui.guide

import android.app.Application
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.data.db.FavouriteEntity
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.ProgrammeEntity
import app.tuned.ui.common.BackTopBar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = app.container.db
    val channel = MutableStateFlow<ItemEntity?>(null)

    fun load(id: Long) {
        if (channel.value?.id == id) return
        viewModelScope.launch { channel.value = db.items().get(id) }
    }

    val programmes: StateFlow<List<ProgrammeEntity>> =
        channel
            .filterNotNull()
            .flatMapLatest { c -> c.tvgId?.let { db.programmes().upcoming(it, System.currentTimeMillis(), 200) } ?: emptyFlow() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favourite: StateFlow<Boolean> =
        channel
            .filterNotNull()
            .flatMapLatest { db.library().isFavourite(it.id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleFavourite() {
        val c = channel.value ?: return
        viewModelScope.launch {
            if (favourite.value) {
                db.library().removeFavourite(
                    c.id,
                )
            } else {
                db.library().addFavourite(FavouriteEntity(c.id, System.currentTimeMillis()))
            }
        }
    }
}

/** A channel's schedule from the downloaded guide: on now first, then what's next, grouped by day. */
@Composable
fun GuideScreen(
    itemId: Long,
    onPlay: (Long) -> Unit,
    onBack: () -> Unit,
    vm: GuideViewModel = viewModel(),
) {
    LaunchedEffect(itemId) { vm.load(itemId) }
    val channel by vm.channel.collectAsStateWithLifecycle()
    val programmes by vm.programmes.collectAsStateWithLifecycle()
    val favourite by vm.favourite.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val time = DateFormat.getTimeFormat(context)
    val day = DateFormat.getMediumDateFormat(context)
    val now = System.currentTimeMillis()

    Scaffold(
        topBar = {
            BackTopBar(channel?.name.orEmpty(), onBack) {
                IconButton(onClick = vm::toggleFavourite) {
                    Icon(
                        if (favourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = stringResource(if (favourite) R.string.favourite_remove else R.string.favourite_add),
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onPlay(itemId) },
                icon = { Icon(Icons.Filled.PlayArrow, null) },
                text = { Text(stringResource(R.string.watch_now)) },
            )
        },
    ) { padding ->
        if (programmes.isEmpty()) {
            Text(
                stringResource(R.string.guide_empty),
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        val rows =
            remember(programmes) {
                programmes.mapIndexed { i, p ->
                    val label = day.format(Date(p.start))
                    Triple(p, label, i == 0 || label != day.format(Date(programmes[i - 1].start)))
                }
            }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(rows, key = { it.first.rowId }) { (p, label, header) ->
                val onNow = p.start <= now && p.stop > now
                Column {
                    if (header) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (onNow) {
                                    Modifier.background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                    )
                                } else {
                                    Modifier
                                },
                            ).clickable(enabled = onNow) { onPlay(itemId) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            time.format(Date(p.start)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 64.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (onNow) stringResource(R.string.guide_on_now, p.title) else p.title,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            p.description?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
