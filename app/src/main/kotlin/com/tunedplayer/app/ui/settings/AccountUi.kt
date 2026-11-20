package com.tunedplayer.app.ui.settings

import android.app.Application
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.XtreamAccount
import com.tunedplayer.app.diagnostics.launchSafely

class AccountViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val account = app.container.account
    val snapshot = account.snapshot

    init {
        // launchSafely, like Home's ask: a failure here used to end the app when Settings opened (2026-11-20).
        viewModelScope.launchSafely(TAG) { account.refresh() }
    }

    private companion object {
        const val TAG = "AccountSettings"
    }
}

/**
 * Settings › Account › Status: whether the account is active, when it runs out, and how many of its connections are
 * in use (Melvin, 2026-11-17, from the app audit). Shown for Xtream logins only; a plain M3U link has no such call.
 */
@Composable
fun AccountStatusRows(vm: AccountViewModel = viewModel()) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val info = snapshot?.info ?: return
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val status = info.status ?: stringResource(R.string.account_status_unknown)
    val expires = info.expiresAt
    val expiry =
        when {
            expires == null -> {
                stringResource(R.string.account_no_end)
            }

            else -> {
                val date = DateUtils.formatDateTime(context, expires, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
                val days = XtreamAccount.daysLeft(expires, now)
                when {
                    days < 0 -> stringResource(R.string.account_expired_on, date)
                    days < SOON_DAYS -> pluralStringResource(R.plurals.account_expires_soon, days.toInt(), date, days.toInt())
                    else -> stringResource(R.string.account_expires_on, date)
                }
            }
        }
    val trial = if (info.trial) stringResource(R.string.account_trial) else null
    SettingRow(stringResource(R.string.account_status), listOfNotNull(status, trial, expiry).joinToString(" · "))
    val active = info.activeConnections
    val max = info.maxConnections
    if (active != null && max != null && max > 0) {
        val summary =
            if (info.full) {
                stringResource(R.string.account_connections_full, active, max)
            } else {
                stringResource(R.string.account_connections, active, max)
            }
        SettingRow(stringResource(R.string.account_connections_title), summary)
    }
}

private const val SOON_DAYS = 7
