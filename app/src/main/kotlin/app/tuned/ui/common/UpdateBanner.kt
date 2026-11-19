package app.tuned.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tuned.R
import app.tuned.container
import app.tuned.updates.GitHubUpdates

/**
 * A newer Tuned was downloaded from GitHub: one tap installs it. Nothing shows otherwise. On Home, and on the welcome
 * screen, so a first install that is already out of date can update before a playlist is added.
 */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val updates = context.container.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val ready = state as? GitHubUpdates.State.Ready ?: return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.update_ready, ready.versionName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            // The outline shows where a TV remote is; the arrows reach Install like any other button (up from the first card
            // on the welcome screen, up from the categories on Home).
            TextButton(
                onClick = { updates.install(context) },
                modifier = Modifier.focusRing(ButtonDefaults.textShape, MaterialTheme.colorScheme.primary),
            ) { Text(stringResource(R.string.update_install)) }
        }
    }
}
