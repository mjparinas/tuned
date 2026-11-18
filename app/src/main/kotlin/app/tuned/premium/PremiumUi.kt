package app.tuned.premium

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tuned.R
import app.tuned.container
import app.tuned.ui.settings.SettingRow

/** True when Premium is unlocked (always, in the `github` build). */
@Composable
fun premiumUnlocked(): Boolean {
    val unlocked by LocalContext.current.container.premium.unlocked
        .collectAsStateWithLifecycle()
    return unlocked
}

/** A Premium setting while it's locked: its title, "Premium" and a lock. A tap opens the Premium screen. */
@Composable
fun LockedRow(title: String) {
    val context = LocalContext.current
    SettingRow(
        title,
        stringResource(R.string.premium_locked),
        onClick = { Premium.open(context) },
        trailing = { Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.premium_locked)) },
    )
}

/** Settings › About › Tuned Premium, in the store build only: whether it's on, and the way to it. */
@Composable
fun PremiumRow() {
    if (Premium.ALWAYS) return
    val context = LocalContext.current
    SettingRow(
        stringResource(R.string.premium_title),
        stringResource(if (premiumUnlocked()) R.string.premium_summary_on else R.string.premium_summary_off),
        onClick = { Premium.open(context) },
    )
}
