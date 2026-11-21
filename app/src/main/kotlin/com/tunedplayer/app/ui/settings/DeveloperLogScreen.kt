package com.tunedplayer.app.ui.settings

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.CrashReport
import com.tunedplayer.app.diagnostics.CrashReporter
import com.tunedplayer.app.diagnostics.LogEntry
import com.tunedplayer.app.diagnostics.LogLevel
import com.tunedplayer.app.diagnostics.ProblemReport
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.diagnostics.startActivitySafely
import com.tunedplayer.app.ui.common.BackTopBar
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.rememberStartFocus
import com.tunedplayer.app.ui.common.startFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class DeveloperLog(
    val crashes: List<CrashReport> = emptyList(),
    /** Newest first. */
    val lines: List<LogEntry> = emptyList(),
)

/** Where the Report button on Logs is: ready, sending (a spinner), or how the last send ended. */
enum class LogReport { READY, SENDING, SENT, FAILED }

class DeveloperLogViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val crashes = MutableStateFlow<List<CrashReport>>(emptyList())
    private val reportState = MutableStateFlow(LogReport.READY)
    val report: StateFlow<LogReport> = reportState

    val log: StateFlow<DeveloperLog> =
        combine(crashes, AppLog.changes.map { AppLog.snapshot().asReversed() }) { c, lines -> DeveloperLog(c, lines) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeveloperLog())

    init {
        loadCrashes()
    }

    private fun loadCrashes() {
        viewModelScope.launchSafely(TAG) {
            crashes.value = withContext(Dispatchers.IO) { CrashReporter.reports(getApplication()) }
        }
    }

    fun clear() {
        viewModelScope.launchSafely(TAG) {
            withContext(Dispatchers.IO) { CrashReporter.clear(getApplication()) }
            AppLog.clear()
            crashes.value = emptyList()
        }
    }

    /**
     * The Report button (Melvin, 2026-11-21): sends the whole log, the latest crash reports and this device's details to
     * Tuned with nothing to type, so it works with a TV remote ([ProblemReport.sendLogsToTuned]). Kept in the view model
     * so a send carries on, and its spinner stays, if the screen turns.
     */
    fun sendReport() {
        if (reportState.value == LogReport.SENDING) return
        reportState.value = LogReport.SENDING
        viewModelScope.launchSafely(TAG, onError = { reportState.value = LogReport.FAILED }) {
            val app = getApplication<Application>()
            val c = app.container
            val sent = withContext(Dispatchers.IO) { ProblemReport.sendLogsToTuned(app, c.http, c.tvSignInUrl, c.usage.idIfOn()) }
            reportState.value = if (sent) LogReport.SENT else LogReport.FAILED
        }
    }

    /** Everything in one plain-text block, newest last, cut to what a share or the clipboard can carry. */
    fun asText(log: DeveloperLog): String = buildString {
        for (crash in log.crashes) {
            appendLine(crash.text)
            appendLine("----")
        }
        log.lines.asReversed().forEach { appendLine(it.format()) }
    }.takeLast(MAX_EXPORT_CHARS)

    private companion object {
        const val TAG = "Logs"

        /** Well under the 1 MB a share or the clipboard can pass between apps. */
        const val MAX_EXPORT_CHARS = 200_000
    }
}

/** Settings › Updates and advanced › Logs: crash reports and the recent log, to read, copy or share. Always shown. */
@Composable
fun DeveloperLogScreen(
    onBack: () -> Unit,
    vm: DeveloperLogViewModel = viewModel(),
) {
    val log by vm.log.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.logs_copied)
    val title = stringResource(R.string.logs_title)
    Scaffold(
        topBar = {
            BackTopBar(title, onBack) {
                IconButton(onClick = {
                    if (copy(context, vm.asText(log)) && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        scope.launch { snackbar.showSnackbar(copied) } // Android 13+ confirms a copy itself
                    }
                }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.logs_copy)) }
                IconButton(onClick = { share(context, title, vm.asText(log)) }) {
                    Icon(Icons.Filled.Share, stringResource(R.string.logs_share))
                }
                IconButton(onClick = vm::clear) { Icon(Icons.Filled.Delete, stringResource(R.string.logs_clear)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "report") { ReportRow(report, vm::sendReport) }
            if (log.crashes.isEmpty() && log.lines.isEmpty()) {
                item { SettingRow(stringResource(R.string.logs_empty), stringResource(R.string.logs_empty_summary)) }
            }
            if (log.crashes.isNotEmpty()) {
                item { SectionHeader(pluralStringResource(R.plurals.logs_crashes, log.crashes.size, log.crashes.size)) }
                items(log.crashes, key = { "crash-${it.timeMs}" }) { CrashRow(it) }
            }
            if (log.lines.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.logs_recent)) }
                items(log.lines) { LogLine(it) }
            }
        }
    }
}

/**
 * Send to Tuned, first on the page and where a TV's remote starts, so OK alone sends it. A spinner while it sends,
 * then a line saying it arrived or didn't, with Try again.
 */
@Composable
internal fun ReportRow(
    state: LogReport,
    onSend: () -> Unit,
) {
    val start = rememberStartFocus()
    val sending = state == LogReport.SENDING
    Row(
        Modifier
            .fillMaxWidth()
            .startFocus(start)
            .focusRing(RectangleShape, MaterialTheme.colorScheme.primary)
            .clickable(enabled = !sending, onClick = onSend)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when (state) {
                        LogReport.SENDING -> R.string.report_sending
                        LogReport.FAILED -> R.string.retry
                        else -> R.string.report_send
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(
                    when (state) {
                        LogReport.READY -> R.string.logs_report_summary
                        LogReport.SENDING -> R.string.logs_report_sending
                        LogReport.SENT -> R.string.report_sent
                        LogReport.FAILED -> R.string.logs_report_failed
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == LogReport.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sending) {
            Spacer(Modifier.width(16.dp))
            RowSpinner()
        }
    }
    HorizontalDivider()
}

@Composable
private fun CrashRow(crash: CrashReport) {
    var open by rememberSaveable(crash.timeMs) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(crash.timeMs)),
            style = MaterialTheme.typography.bodyLarge,
        )
        val headline =
            crash.text
                .lineSequence()
                .drop(4)
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        if (open) {
            SelectionContainer { Text(crash.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        } else {
            Text(headline, maxLines = 2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
    HorizontalDivider()
}

@Composable
private fun LogLine(entry: LogEntry) {
    val colour =
        when (entry.level) {
            LogLevel.ERROR -> MaterialTheme.colorScheme.error
            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurface
        }
    SelectionContainer {
        Text(
            entry.format(),
            color = colour,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        )
    }
}

private fun copy(
    context: Context,
    text: String,
): Boolean = attempt("Logs", "Couldn't copy the log") {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: error("No clipboard")
    clipboard.setPrimaryClip(ClipData.newPlainText("Tuned log", text))
}.isSuccess

private fun share(
    context: Context,
    title: String,
    text: String,
) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, text)
    context.startActivitySafely(Intent.createChooser(send, title), "Logs")
}
