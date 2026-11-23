package com.tunedplayer.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R
import com.tunedplayer.app.data.Credentials
import com.tunedplayer.app.ui.common.BackTopBar

/** Plex, Jellyfin or Emby: one card on the welcome screen leads here so a TV's kinds list stays short. */
@Composable
fun ChooseMediaServerScreen(
    onPick: (Credentials.Kind) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.kind_media), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.add_media_pick),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KindCard(Icons.Filled.VideoLibrary, R.string.kind_plex, R.string.kind_plex_summary) {
                onPick(Credentials.Kind.Plex)
            }
            KindCard(Icons.Filled.Storage, R.string.kind_jellyfin, R.string.kind_jellyfin_summary) {
                onPick(Credentials.Kind.Jellyfin)
            }
            KindCard(Icons.Filled.Tv, R.string.kind_emby, R.string.kind_emby_summary) {
                onPick(Credentials.Kind.Emby)
            }
        }
    }
}
