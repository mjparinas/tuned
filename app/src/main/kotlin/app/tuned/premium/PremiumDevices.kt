package app.tuned.premium

import android.app.Activity
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tuned.BuildConfig
import app.tuned.R
import app.tuned.core.AccountSeats
import app.tuned.core.DeviceSeats
import app.tuned.core.SeatDevice
import app.tuned.data.AccountPlan
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.common.TunedDialog

/**
 * The devices using this purchase, on the Premium screen (docs/decisions/premium.md). On a device with a seat, each
 * one has Deactivate; on a device that's locked out, the list says which devices hold the seats, and Use Premium on
 * this device appears once one is free.
 */
@Composable
internal fun PremiumDevices(
    premium: Premium,
    activity: Activity,
) {
    if (!premium.limited) return
    val unlocked by premium.unlocked.collectAsStateWithLifecycle()
    val purchased by premium.purchased.collectAsStateWithLifecycle()
    val devices by premium.devices.collectAsStateWithLifecycle()
    val plan by premium.plan.collectAsStateWithLifecycle()
    if (!purchased) return
    val limit = plan?.limit ?: DeviceSeats.LIMIT
    // The list as it is now, in the background; the screen shows the last answer meanwhile.
    LaunchedEffect(Unit) { premium.refresh() }
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<SeatDevice?>(null) }
    var note by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.premium_devices_title), style = MaterialTheme.typography.titleMedium)
        val others = devices.count { !it.current }
        Text(
            when {
                unlocked -> pluralStringResource(R.plurals.premium_devices_lead, limit, limit)
                others >= limit -> pluralStringResource(R.plurals.premium_devices_full, others, others)
                else -> stringResource(R.string.premium_devices_off_here)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        devices.forEach { device ->
            DeviceRow(device, canDeactivate = unlocked) { confirm = device }
        }
        if (!unlocked && others >= limit && plan == null) {
            // Someone who has lost every device with a seat frees them on the website with their order number.
            Text(
                stringResource(R.string.premium_devices_site, BuildConfig.DEVICES_URL.trimEnd('/').removePrefix("https://")),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!unlocked && others < limit) {
            Button(
                onClick = {
                    scope.launchSafely(TAG) {
                        premium.useHere()
                        note = if (premium.unlocked.value) null else R.string.premium_devices_unreachable
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.premium_devices_use_here)) }
        }
        note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
        plan?.let { if (unlocked) MoreDevices(premium, it, activity) }
    }
    confirm?.let { device ->
        TunedDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.premium_devices_confirm_title, device.name)) },
            text = {
                Text(
                    stringResource(
                        if (device.current) R.string.premium_devices_confirm_this else R.string.premium_devices_confirm_other,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launchSafely(TAG) {
                        note = if (premium.deactivate(device)) null else R.string.premium_devices_unreachable
                    }
                }) { Text(stringResource(R.string.premium_devices_deactivate)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/**
 * Extra seats on a signed-in account, from Play (docs/decisions/accounts.md): one-time seats for lifetime Premium,
 * yearly ones for yearly Premium, never the other kind. Nothing here points to the website: the Play build sells
 * only through Play.
 */
@Composable
private fun MoreDevices(
    premium: Premium,
    plan: AccountPlan,
    activity: Activity,
) {
    val product = AccountSeats.seatProduct(plan.premium) ?: return
    val most = AccountSeats.playCanBuy(product, plan.canBuy, plan.ownsYearlySeat)
    if (most <= 0) return
    var offer by remember { mutableStateOf<Offer?>(null) }
    LaunchedEffect(product) { offer = premium.store.seatOffer(product) }
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    offer?.let { found ->
        MoreDevicesOffer(found, most, failed) {
            scope.launchSafely(TAG) { failed = !premium.store.buy(activity, found.productId) }
        }
    }
}

/** The extra-seat offer as drawn: what it costs, how many more, and the button that opens Play's purchase sheet. */
@Composable
internal fun MoreDevicesOffer(
    offer: Offer,
    most: Int,
    failed: Boolean,
    onBuy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.premium_more_title), style = MaterialTheme.typography.titleMedium)
        Text(
            if (offer.yearly) {
                stringResource(R.string.premium_more_yearly_lead, offer.price)
            } else {
                pluralStringResource(R.plurals.premium_more_once_lead, most, most, offer.price)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onBuy, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (offer.yearly) R.string.premium_more_buy_yearly else R.string.premium_more_buy_once,
                    offer.price,
                ),
            )
        }
        if (failed) Text(stringResource(R.string.premium_store_unreachable), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeviceRow(
    device: SeatDevice,
    canDeactivate: Boolean,
    onDeactivate: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (device.current) {
                    stringResource(R.string.premium_devices_this_one)
                } else {
                    stringResource(
                        R.string.premium_devices_last_seen,
                        DateUtils.getRelativeTimeSpanString(device.lastSeen, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (canDeactivate) TextButton(onClick = onDeactivate) { Text(stringResource(R.string.premium_devices_deactivate)) }
    }
}

private const val TAG = "Premium"
