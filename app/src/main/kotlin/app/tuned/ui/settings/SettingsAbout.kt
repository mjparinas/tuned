package app.tuned.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tuned.BuildConfig
import app.tuned.R
import app.tuned.container
import app.tuned.data.AppSettings
import app.tuned.data.UsageChoice
import app.tuned.diagnostics.ProblemReport
import app.tuned.diagnostics.launchSafely
import app.tuned.premium.PremiumRow
import app.tuned.ui.DeveloperLogRoute
import app.tuned.ui.common.TunedDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AboutPage(
    settings: AppSettings,
    vm: SettingsViewModel,
    onOpen: (Any) -> Unit,
) {
    PremiumRow()
    // Store builds update through their store, so there is nothing to turn on or off here.
    if (BuildConfig.GITHUB_UPDATES) {
        SwitchRow(
            stringResource(R.string.settings_check_updates),
            settings.checkUpdates,
            stringResource(R.string.settings_check_updates_summary),
        ) { on -> vm.update { it.copy(checkUpdates = on) } }
        UpdateCheckRow()
    }
    SectionHeader(stringResource(R.string.settings_advanced))
    UserAgentRow(settings.userAgent) { ua -> vm.update { it.copy(userAgent = ua) } }
    SectionHeader(stringResource(R.string.settings_header_privacy))
    UsageStatsRow(settings) { choice -> vm.update { it.copy(usageStats = choice) } }
    SectionHeader(stringResource(R.string.settings_header_troubleshooting))
    ReportProblemRow()
    SettingRow(
        stringResource(R.string.logs_title),
        stringResource(R.string.settings_logs_summary),
        onClick = { onOpen(DeveloperLogRoute) },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
}

/**
 * Usage stats (docs/decisions/admin.md): on outside the EU, EEA, UK and Switzerland until switched off, off inside
 * them until switched on. The install id shows while they're on, so a report can be matched to it.
 */
@Composable
private fun UsageStatsRow(
    settings: AppSettings,
    onChange: (UsageChoice) -> Unit,
) {
    val usage = LocalContext.current.container.usage
    val on = remember(settings.usageStats) { usage.decide(settings.usageStats) }
    val id by produceState<String?>(null, on) { value = usage.idIfOn() }
    val summary = stringResource(R.string.settings_usage_stats_summary)
    SwitchRow(
        stringResource(R.string.settings_usage_stats),
        on,
        id?.let { stringResource(R.string.settings_usage_stats_id, summary, it) } ?: summary,
    ) { checked -> onChange(if (checked) UsageChoice.ON else UsageChoice.OFF) }
}

/**
 * Report a problem (Melvin, 2026-11-19): gathers the version, device, latest crash or freeze and the recent log, with
 * no login. The person says what happened and sends it to Tuned (the admin's Reports), or shares it from the share
 * sheet so they pick where it goes ([ProblemReport]).
 */
@Composable
private fun ReportProblemRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<Int?>(null) }
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    SettingRow(
        stringResource(R.string.report_title),
        stringResource(outcome ?: R.string.report_summary),
        onClick = { if (!busy) asking = true },
    )
    if (asking) {
        ReportDialog(
            busy = busy,
            onDismiss = { if (!busy) asking = false },
            onShare = {
                asking = false
                busy = true
                scope.launchSafely("Report", onError = { busy = false }) {
                    val report = withContext(Dispatchers.IO) { ProblemReport.build(context.applicationContext) }
                    outcome =
                        when (ProblemReport.send(context, report)) {
                            ProblemReport.Outcome.COPIED -> R.string.report_copied
                            ProblemReport.Outcome.FAILED -> R.string.report_failed
                            ProblemReport.Outcome.SHARED -> null
                        }
                    busy = false
                }
            },
            onSend = { what, contact ->
                busy = true
                scope.launchSafely("Report", onError = { busy = false }) {
                    val app = context.applicationContext
                    val c = app.container
                    val sent =
                        withContext(Dispatchers.IO) {
                            ProblemReport.sendToTuned(app, c.http, c.tvSignInUrl, what, contact, c.usage.idIfOn())
                        }
                    outcome = if (sent) R.string.report_sent else R.string.report_send_failed
                    busy = false
                    asking = false
                }
            },
        )
    }
}

/** What happened, an email for a reply if they want one, then Send to Tuned or Share instead. */
@Composable
private fun ReportDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSend: (what: String, contact: String) -> Unit,
) {
    var what by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    TunedDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.report_dialog_text), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = what,
                    onValueChange = { what = it.take(WHAT_LIMIT) },
                    label = { Text(stringResource(R.string.report_what)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it.take(CONTACT_LIMIT) },
                    label = { Text(stringResource(R.string.report_contact)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(what, contact) }, enabled = !busy && what.isNotBlank()) {
                Text(stringResource(if (busy) R.string.report_sending else R.string.report_send))
            }
        },
        dismissButton = { TextButton(onClick = onShare, enabled = !busy) { Text(stringResource(R.string.report_share_instead)) } },
    )
}

private const val WHAT_LIMIT = 4_000
private const val CONTACT_LIMIT = 200
