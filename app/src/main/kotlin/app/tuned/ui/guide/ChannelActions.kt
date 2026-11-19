package app.tuned.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tuned.R
import app.tuned.ui.browse.rememberChannelEditPicker
import app.tuned.ui.browse.rememberListPicker

/**
 * What a channel's long-press menu holds, as buttons at the top of its page (Melvin, 2026-11-19: "Optimize TV for remotes,
 * avoid 3 dot drop downs"). Holding OK on a remote is something few people find, so every channel's page shows them in
 * sight; a long press in the list stays as a shortcut on phones.
 */
@Composable
internal fun ChannelActions(
    channelId: Long,
    modifier: Modifier = Modifier,
) {
    val lists = rememberListPicker()
    val edits = rememberChannelEditPicker()
    FlowRow(
        modifier.fillMaxWidth().padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Action(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.list_add)) { lists.open(channelId) }
        Action(Icons.Filled.DriveFileRenameOutline, stringResource(R.string.channel_rename)) { edits.rename(channelId) }
        Action(Icons.Filled.Image, stringResource(R.string.channel_change_logo)) { edits.changeLogo(channelId) }
    }
}

@Composable
private fun Action(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        label = { Text(label) },
    )
}
