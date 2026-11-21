package com.tunedplayer.app.premium

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.common.BackTopBar
import com.tunedplayer.app.ui.common.TvSafeArea
import com.tunedplayer.app.ui.theme.TunedTheme

/**
 * The one Premium screen (docs/decisions/premium.md): what's included, the two prices from the store, the trial, and
 * Restore purchase. It opens only when someone taps a locked feature or Settings › Tuned Premium; nothing else asks.
 */
class PremiumActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val s by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val current = s ?: return@setContent
            TunedTheme(
                mode = current.theme,
                dynamicColour = current.dynamicColour,
                pureBlack = current.pureBlack,
                accent = current.accent,
            ) {
                TvSafeArea { PremiumScreen(this, onBack = ::finish) }
            }
        }
    }
}

@Composable
private fun PremiumScreen(
    activity: ComponentActivity,
    onBack: () -> Unit,
) {
    val premium = activity.container.premium
    val unlocked by premium.unlocked.collectAsStateWithLifecycle()
    val purchased by premium.purchased.collectAsStateWithLifecycle()
    var offers by remember { mutableStateOf<List<Offer>?>(null) }
    var note by remember { mutableStateOf<Int?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(unlocked) { if (!unlocked && !purchased) offers = premium.store.offers() }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.premium_title), onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (unlocked) R.string.premium_on else R.string.premium_lead),
                style = MaterialTheme.typography.titleMedium,
            )
            FEATURES.forEach { Feature(stringResource(it)) }
            Spacer(Modifier.height(8.dp))
            // Bought, but every seat is taken or this device gave its own up: the devices list, not the prices.
            PremiumDevices(premium, activity)
            if (!unlocked && !purchased) {
                val list = offers
                when {
                    list == null -> {
                        Text(stringResource(R.string.premium_loading), style = MaterialTheme.typography.bodyMedium)
                    }

                    list.isEmpty() -> {
                        Text(stringResource(R.string.premium_store_unreachable), style = MaterialTheme.typography.bodyMedium)
                    }

                    else -> {
                        list.sortedBy { !it.yearly }.forEach { offer ->
                            val label = offerLabel(offer)
                            val buy = {
                                scope.launchSafely(TAG) {
                                    if (!premium.store.buy(activity, offer.productId)) {
                                        note =
                                            R.string.premium_store_unreachable
                                    }
                                }
                            }
                            if (offer.yearly) {
                                Button(onClick = { buy() }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { buy() }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                            }
                        }
                    }
                }
                // A spinner where the words were while the store answers, so the tap is never met with nothing
                // (Melvin, 2026-11-21); a second tap meanwhile does nothing.
                TextButton(
                    onClick = {
                        if (!restoring) {
                            restoring = true
                            note = null
                            scope.launchSafely(TAG, onError = {
                                restoring = false
                                note = R.string.premium_store_unreachable
                            }) {
                                note =
                                    when (premium.restore()) {
                                        true -> null
                                        false -> R.string.premium_nothing_to_restore
                                        null -> R.string.premium_store_unreachable
                                    }
                                restoring = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (restoring) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.premium_restore))
                    }
                }
                note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.premium_fine_print), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun offerLabel(offer: Offer): String = when {
    !offer.yearly -> stringResource(R.string.premium_buy_lifetime, offer.price)
    offer.trialDays > 0 -> pluralStringResource(R.plurals.premium_try_free, offer.trialDays, offer.trialDays, offer.price)
    else -> stringResource(R.string.premium_buy_yearly, offer.price)
}

@Composable
private fun Feature(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

private const val TAG = "Premium"

private val FEATURES =
    listOf(
        R.string.premium_feature_playlists,
        R.string.premium_feature_profiles,
        R.string.premium_feature_avatars,
        R.string.premium_feature_rewind,
        R.string.premium_feature_record,
        R.string.premium_feature_downloads,
        R.string.premium_feature_events,
        R.string.premium_feature_sync,
        R.string.premium_feature_trakt,
        R.string.premium_feature_colours,
    )
