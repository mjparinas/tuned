package app.tuned.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tuned.R
import app.tuned.data.Credentials
import app.tuned.ui.common.BackTopBar
import app.tuned.ui.common.focusRing

/**
 * The first thing a new install shows, and Settings › Playlists › Add: one question, "what kind of playlist?", with
 * the three kinds providers hand out. The form for the chosen kind comes next ([AddPlaylistScreen]), so nobody faces
 * a server, username, password and two links at once.
 */
@Composable
fun WelcomeScreen(
    firstRun: Boolean,
    onPick: (Credentials.Kind) -> Unit,
    onBack: () -> Unit,
    /** First run: Restore from Google Drive brought everything back ([RestoreFromDrive]). */
    onRestored: (askProfile: Boolean) -> Unit = {},
) {
    Scaffold(topBar = { if (!firstRun) BackTopBar(stringResource(R.string.playlists_add), onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (firstRun) {
                Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(88.dp))
                Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.welcome_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 480.dp),
                )
                Spacer(Modifier.height(8.dp))
            } else {
                Text(
                    stringResource(R.string.welcome_pick),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                )
            }
            KindCard(Icons.Filled.Dns, R.string.kind_xtream, R.string.kind_xtream_summary) { onPick(Credentials.Kind.Xtream) }
            KindCard(Icons.Filled.Link, R.string.kind_link, R.string.kind_link_summary) { onPick(Credentials.Kind.Link) }
            KindCard(Icons.Filled.FolderOpen, R.string.kind_file, R.string.kind_file_summary) { onPick(Credentials.Kind.File) }
            if (firstRun) RestoreFromDrive(onRestored)
            if (firstRun) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.login_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun KindCard(
    icon: ImageVector,
    title: Int,
    summary: Int,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    OutlinedCard(
        onClick = onClick,
        shape = shape,
        modifier =
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .focusRing(shape, MaterialTheme.colorScheme.primary),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
