package app.tuned.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.tuned.BuildConfig
import app.tuned.R
import app.tuned.data.AppSettings
import app.tuned.diagnostics.ProblemReport
import app.tuned.diagnostics.launchSafely
import app.tuned.premium.PremiumRow
import app.tuned.ui.DeveloperLogRoute
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
 * Report a problem (Melvin, 2026-11-19): gathers the version, device, latest crash or freeze and the recent log, with
 * no login, and opens the share sheet so the person picks where it goes ([ProblemReport]).
 */
@Composable
private fun ReportProblemRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<ProblemReport.Outcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    SettingRow(
        stringResource(R.string.report_title),
        stringResource(
            when (outcome) {
                ProblemReport.Outcome.COPIED -> R.string.report_copied
                ProblemReport.Outcome.FAILED -> R.string.report_failed
                else -> R.string.report_summary
            },
        ),
        onClick = {
            if (!busy) {
                busy = true
                scope.launchSafely("Report", onError = { busy = false }) {
                    val report = withContext(Dispatchers.IO) { ProblemReport.build(context.applicationContext) }
                    outcome = ProblemReport.send(context, report)
                    busy = false
                }
            }
        },
    )
}
