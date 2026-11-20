package com.tunedplayer.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R

/**
 * Every screen, with a strip across the top while the device is offline. The strip takes the status bar's space, so
 * the screen below it doesn't leave a second gap for the status bar. [onOpenDownloads] is null where a Downloads
 * button makes no sense (signed out, or already on Downloads).
 */
@Composable
fun WithOfflineBanner(
    offline: Boolean,
    onOpenDownloads: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OfflineBanner(offline, onOpenDownloads)
        val below = if (offline) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
        Box(Modifier.fillMaxSize().then(below)) { content() }
    }
}

@Composable
private fun ColumnScope.OfflineBanner(
    offline: Boolean,
    onOpenDownloads: (() -> Unit)?,
) {
    AnimatedVisibility(visible = offline, enter = expandVertically(), exit = shrinkVertically()) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.offline_banner),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                if (onOpenDownloads != null) {
                    TextButton(onClick = onOpenDownloads) { Text(stringResource(R.string.offline_banner_downloads)) }
                }
            }
        }
    }
}
