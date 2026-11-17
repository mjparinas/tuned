package app.tuned.ui.offline

import android.app.Application
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.data.db.DownloadEntity
import app.tuned.data.db.ItemEntity
import app.tuned.ui.common.BackTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val offline = app.container.offline

    val rows: StateFlow<List<DownloadEntity>> = offline.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val byItem: StateFlow<Map<Long, DownloadEntity>> =
        offline.all
            .map { list ->
                list.associateBy { it.itemId }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun add(item: ItemEntity) {
        viewModelScope.launch { offline.add(item) }
    }

    fun remove(itemId: Long) {
        viewModelScope.launch { offline.remove(itemId) }
    }

    fun retry(itemId: Long) {
        viewModelScope.launch { offline.retry(itemId) }
    }
}

/** Download, its progress, done (tap to remove) or failed (tap to try again), for a film or episode. */
@Composable
fun DownloadButton(
    item: ItemEntity,
    vm: DownloadsViewModel = viewModel(),
) {
    val byItem by vm.byItem.collectAsStateWithLifecycle()
    val row = byItem[item.id]
    when (row?.state) {
        null -> {
            IconButton(onClick = { vm.add(item) }) {
                Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.download))
            }
        }

        DownloadEntity.DONE -> {
            IconButton(onClick = { vm.remove(item.id) }) {
                Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = stringResource(R.string.download_remove),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        DownloadEntity.FAILED -> {
            IconButton(onClick = { vm.retry(item.id) }) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.download_retry))
            }
        }

        else -> {
            IconButton(onClick = { vm.remove(item.id) }) {
                Box(contentAlignment = Alignment.Center) {
                    val fraction = row.fraction()
                    if (fraction == null) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

private fun DownloadEntity.fraction(): Float? = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null

/** Settings › Downloads: everything saved or on its way, newest first. Tap a finished one to play it. */
@Composable
fun DownloadsScreen(
    onPlay: (Long) -> Unit,
    onBack: () -> Unit,
    vm: DownloadsViewModel = viewModel(),
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = { BackTopBar(stringResource(R.string.downloads), onBack) }) { padding ->
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.downloads_empty),
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(rows, key = { it.itemId }) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = row.state == DownloadEntity.DONE) { onPlay(row.itemId) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val status =
                            when (row.state) {
                                DownloadEntity.DONE -> {
                                    Formatter.formatShortFileSize(context, row.bytes)
                                }

                                DownloadEntity.FAILED -> {
                                    stringResource(R.string.download_failed)
                                }

                                else -> {
                                    if (row.total > 0) {
                                        stringResource(
                                            R.string.download_progress,
                                            Formatter.formatShortFileSize(context, row.bytes),
                                            Formatter.formatShortFileSize(context, row.total),
                                        )
                                    } else {
                                        stringResource(R.string.download_waiting)
                                    }
                                }
                            }
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (row.state == DownloadEntity.QUEUED) {
                            Spacer(Modifier.height(6.dp))
                            val fraction = row.fraction()
                            if (fraction == null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth(0.6f))
                            } else {
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(0.6f))
                            }
                        }
                    }
                    if (row.state == DownloadEntity.FAILED) {
                        IconButton(onClick = { vm.retry(row.itemId) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.download_retry))
                        }
                    }
                    IconButton(onClick = { vm.remove(row.itemId) }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.download_remove))
                    }
                }
            }
        }
    }
}
