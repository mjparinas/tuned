package app.tuned.ui.settings

import android.app.Application
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.ChannelScan
import app.tuned.core.ScanEngine
import app.tuned.data.AppSettings
import app.tuned.data.ChannelScanner
import app.tuned.data.db.ScanReportRow
import app.tuned.ui.common.BackTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChannelHealthViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container

    val summary: StateFlow<ChannelScanner.Summary?> =
        container.channelScan.summary.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val progress: StateFlow<Pair<Int, Int>?> =
        container.scanControl.progress.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val report: StateFlow<List<ScanReportRow>> =
        container.db
            .channelScan()
            .report()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun start() = container.scanControl.start()

    fun stop() = container.scanControl.stop()

    fun keep(itemId: Long) {
        viewModelScope.launch { container.channelScan.keep(itemId) }
    }

    fun setHideDead(on: Boolean) {
        viewModelScope.launch { container.settings.update { it.copy(hideDeadChannels = on) } }
    }
}

/**
 * Settings › Channel health: scan every live channel, one at a time, and see what failed. The report says why a scan
 * stopped early (no internet, the server, a full account) rather than blaming channels, lists the channels hidden
 * after failing in two scans with a "Show again" for each, and the ones that failed once and get another chance.
 */
@Composable
fun ChannelHealthScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    vm: ChannelHealthViewModel = viewModel(),
) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val hidden = report.filter { it.hidden }
    val once = report.filterNot { it.hidden }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.health_title), onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                ScanCard(summary, progress, onStart = vm::start, onStop = vm::stop)
            }
            item {
                SwitchRow(
                    stringResource(R.string.health_hide_dead),
                    settings.hideDeadChannels,
                    stringResource(R.string.health_hide_dead_summary),
                ) { on -> vm.setHideDead(on) }
            }
            if (hidden.isNotEmpty()) {
                item { SectionHeader(pluralStringResource(R.plurals.health_hidden, hidden.size, hidden.size)) }
                items(hidden, key = { "h:${it.itemId}" }) { row -> ReportRow(row, onKeep = { vm.keep(row.itemId) }) }
            }
            if (once.isNotEmpty()) {
                item { SectionHeader(pluralStringResource(R.plurals.health_once, once.size, once.size)) }
                items(once, key = { "o:${it.itemId}" }) { row -> ReportRow(row, onKeep = null) }
            }
        }
    }
}

@Composable
private fun ScanCard(
    summary: ChannelScanner.Summary?,
    progress: Pair<Int, Int>?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.health_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress != null) {
            val (done, total) = progress
            Text(
                if (total > 0) stringResource(R.string.scan_progress, done, total) else stringResource(R.string.health_starting),
                style = MaterialTheme.typography.titleSmall,
            )
            if (total > 0) {
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                stringResource(R.string.health_while_scanning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.health_stop)) }
        } else {
            summary?.let { LastScan(it) }
            Button(onClick = onStart) { Text(stringResource(R.string.health_scan_now)) }
        }
    }
}

@Composable
private fun LastScan(s: ChannelScanner.Summary) {
    val ago = DateUtils.getRelativeTimeSpanString(s.finishedAt).toString()
    Text(stringResource(R.string.health_last_scan, ago), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.health_counts, s.working, s.failed, s.unsure),
        style = MaterialTheme.typography.bodyMedium,
    )
    val stopped =
        when (s.outcome) {
            ScanEngine.Outcome.DONE -> null
            ScanEngine.Outcome.NO_INTERNET -> R.string.health_stopped_internet
            ScanEngine.Outcome.SERVER_DOWN -> R.string.health_stopped_server
            ScanEngine.Outcome.ACCOUNT_BUSY -> R.string.health_stopped_busy
        }
    if (stopped != null) {
        Text(stringResource(stopped), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ReportRow(
    row: ScanReportRow,
    onKeep: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val ago = DateUtils.getRelativeTimeSpanString(row.lastFailAt).toString()
            Text(
                stringResource(R.string.health_row, row.groupName, stringResource(reasonText(row.reason)), ago),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onKeep != null) {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.health_show_again)) }
        }
    }
}

private fun reasonText(reason: String?): Int = when (ChannelScan.Failure.entries.firstOrNull { it.name == reason }) {
    ChannelScan.Failure.HTTP -> R.string.health_reason_http
    ChannelScan.Failure.NO_DATA -> R.string.health_reason_no_data
    ChannelScan.Failure.NOT_VIDEO -> R.string.health_reason_not_video
    ChannelScan.Failure.TIMEOUT -> R.string.health_reason_timeout
    ChannelScan.Failure.CONNECTION -> R.string.health_reason_connection
    ChannelScan.Failure.ACCOUNT -> R.string.health_reason_account
    null -> R.string.health_reason_http
}
