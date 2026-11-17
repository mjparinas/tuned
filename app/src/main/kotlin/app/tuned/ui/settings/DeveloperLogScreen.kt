package app.tuned.ui.settings

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.CrashReport
import app.tuned.diagnostics.CrashReporter
import app.tuned.diagnostics.LogEntry
import app.tuned.diagnostics.LogLevel
import app.tuned.diagnostics.attempt
import app.tuned.diagnostics.launchSafely
import app.tuned.diagnostics.startActivitySafely
import app.tuned.ui.common.BackTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
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

class DeveloperLogViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val crashes = MutableStateFlow<List<CrashReport>>(emptyList())

    val log: StateFlow<DeveloperLog> =
        combine(crashes, AppLog.changes.conflate().map { AppLog.snapshot().asReversed() }) { c, lines -> DeveloperLog(c, lines) }
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

/** Settings › Developer › Logs: crash reports and the recent log, to read, copy or share. Shown in developer mode. */
@Composable
fun DeveloperLogScreen(
    onBack: () -> Unit,
    vm: DeveloperLogViewModel = viewModel(),
) {
    val log by vm.log.collectAsStateWithLifecycle()
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
