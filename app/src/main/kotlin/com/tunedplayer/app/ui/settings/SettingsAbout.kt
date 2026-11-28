package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.UsageChoice
import com.tunedplayer.app.diagnostics.ProblemReport
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.premium.PremiumRow
import com.tunedplayer.app.ui.DeveloperLogRoute
import com.tunedplayer.app.ui.common.DialogWideMaxWidth
import com.tunedplayer.app.ui.common.TunedDialog
import com.tunedplayer.app.ui.common.isTelevision
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
    // A TV has no keyboard to say what happened (Melvin, 2026-11-21: "on android tv, remove report a problem because
    // people can't type"); there, Logs' Report button sends everything with one press.
    if (!isTelevision()) ReportProblemRow()
    SettingRow(
        stringResource(R.string.logs_title),
        stringResource(R.string.settings_logs_summary),
        onClick = { onOpen(DeveloperLogRoute) },
        trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
    SpeedTraceRow()
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
 * Report a problem (Melvin, 2026-11-19): the person says what happened and Send to Tuned sends it to the admin's Reports
 * with everything a fix needs, gathered on its own: the recent log, the latest crash or freeze and this device's details,
 * with no login ([ProblemReport.sendToTuned]). Since 2026-11-21 (Melvin: "can you also get rid of the share instead?")
 * there is no share sheet. The dialog stays open with a spinner while it sends; a failed send keeps what was typed and
 * offers Try again, and a sent one closes and the row says so.
 */
@Composable
private fun ReportProblemRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sent by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    SettingRow(
        stringResource(R.string.report_title),
        stringResource(if (sent) R.string.report_sent else R.string.report_summary),
        onClick = {
            if (!busy) {
                failed = false
                asking = true
            }
        },
    )
    if (asking) {
        ReportDialog(
            busy = busy,
            failed = failed,
            onDismiss = { if (!busy) asking = false },
            onSend = { what, contact ->
                busy = true
                failed = false
                scope.launchSafely(
                    "Report",
                    onError = {
                        busy = false
                        failed = true
                    },
                ) {
                    val app = context.applicationContext
                    val c = app.container
                    val ok =
                        withContext(Dispatchers.IO) {
                            ProblemReport.sendToTuned(app, c.http, c.tvSignInUrl, what, contact, c.usage.idIfOn())
                        }
                    busy = false
                    failed = !ok
                    if (ok) {
                        sent = true
                        asking = false
                    }
                }
            },
        )
    }
}

/**
 * What happened and an email for a reply if they want one, then Send to Tuned. Nearly the screen's width on a phone
 * (Melvin, 2026-11-21: "Can you make this wider?"), up to [DialogWideMaxWidth] on a tablet.
 */
@Composable
internal fun ReportDialog(
    busy: Boolean,
    failed: Boolean,
    onDismiss: () -> Unit,
    onSend: (what: String, contact: String) -> Unit,
    startWhat: String = "",
) {
    var what by remember { mutableStateOf(startWhat) }
    var contact by remember { mutableStateOf("") }
    TunedDialog(
        onDismissRequest = onDismiss,
        maxWidth = DialogWideMaxWidth,
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
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it.take(CONTACT_LIMIT) },
                    label = { Text(stringResource(R.string.report_contact)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy || failed) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (busy) {
                            RowSpinner()
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            stringResource(if (busy) R.string.logs_report_sending else R.string.logs_report_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(what, contact) }, enabled = !busy && what.isNotBlank()) {
                Text(
                    stringResource(
                        when {
                            busy -> R.string.report_sending
                            failed -> R.string.retry
                            else -> R.string.report_send
                        },
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val WHAT_LIMIT = 4_000
private const val CONTACT_LIMIT = 200
