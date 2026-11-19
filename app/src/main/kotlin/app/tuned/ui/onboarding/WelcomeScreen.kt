package app.tuned.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tuned.R
import app.tuned.data.Credentials
import app.tuned.ui.common.BackTopBar
import app.tuned.ui.common.UpdateBanner
import app.tuned.ui.common.focusRing

/**
 * The first thing a new install shows, and Settings › Playlists › Add: one question, "what kind of playlist?", with
 * the kinds providers hand out (an Xtream login, a link, a file or a MAC portal). The form for the chosen kind comes
 * next ([AddPlaylistScreen]), so nobody faces a server, username, password and two links at once. On
 * a TV, signing in with a phone ([TvSignInScreen]) comes first.
 */
@Composable
fun WelcomeScreen(
    firstRun: Boolean,
    onPick: (Credentials.Kind) -> Unit,
    onBack: () -> Unit,
    onPhone: (() -> Unit)? = null,
    /** First run: Restore from your Tuned account brought everything back ([RestoreFromAccount]). */
    onRestored: (askProfile: Boolean) -> Unit = {},
) {
    Scaffold(topBar = { if (!firstRun) BackTopBar(stringResource(R.string.playlists_add), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            // The GitHub build checks for a newer release on every start, signed in or not, so a first install that is
            // already out of date offers the update here, before a playlist is added.
            if (firstRun) UpdateBanner(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp))
            WelcomeBody(firstRun, onPick, onRestored, onPhone)
        }
    }
}

@Composable
private fun WelcomeBody(
    firstRun: Boolean,
    onPick: (Credentials.Kind) -> Unit,
    onRestored: (askProfile: Boolean) -> Unit,
    onPhone: (() -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A TV (960 by 540 dp, less its overscan margin) or a phone held sideways is too short for the logo, the
        // welcome and the cards in one column, which pushed the logo off the top of a real TV. There the welcome
        // sits beside the cards instead, so the whole screen fits without scrolling.
        if (firstRun && maxWidth >= SIDE_BY_SIDE_MIN_WIDTH && maxHeight < SIDE_BY_SIDE_MAX_HEIGHT) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Welcome(logoSize = 72.dp)
                    // Restore and the privacy line sit under the welcome, so the right column holds only the four
                    // kinds and fits a TV's height (480 dp on some, less the overscan margin) without scrolling.
                    RestoreFromAccount(onRestored)
                    Privacy()
                }
                Column(
                    Modifier.weight(1.2f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Kinds(onPick, onPhone)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (firstRun) {
                    Welcome(logoSize = 88.dp)
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(
                        stringResource(R.string.welcome_pick),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    )
                }
                Kinds(onPick, onPhone)
                if (firstRun) RestoreFromAccount(onRestored)
                if (firstRun) {
                    Spacer(Modifier.height(4.dp))
                    Privacy()
                }
            }
        }
    }
}

/** Wider than a phone held upright, so two columns have room. */
private val SIDE_BY_SIDE_MIN_WIDTH = 600.dp

/** Shorter than the single column needs (about 700 dp with the logo, the welcome, four cards, restore and the privacy line). */
private val SIDE_BY_SIDE_MAX_HEIGHT = 620.dp

@Composable
private fun Welcome(logoSize: Dp) {
    Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(logoSize))
    Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Text(
        stringResource(R.string.welcome_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 480.dp),
    )
}

@Composable
private fun Kinds(
    onPick: (Credentials.Kind) -> Unit,
    onPhone: (() -> Unit)?,
) {
    // On a TV, typing a login with the remote is the hard part, so the phone comes first (onboarding.md).
    onPhone?.let { KindCard(Icons.Filled.PhoneAndroid, R.string.tv_signin_card, R.string.tv_signin_card_summary, it) }
    KindCard(Icons.Filled.Dns, R.string.kind_xtream, R.string.kind_xtream_summary) { onPick(Credentials.Kind.Xtream) }
    KindCard(Icons.Filled.Link, R.string.kind_link, R.string.kind_link_summary) { onPick(Credentials.Kind.Link) }
    KindCard(Icons.Filled.FolderOpen, R.string.kind_file, R.string.kind_file_summary) { onPick(Credentials.Kind.File) }
    KindCard(Icons.Filled.Router, R.string.kind_stalker, R.string.kind_stalker_summary) { onPick(Credentials.Kind.Stalker) }
}

@Composable
private fun Privacy() {
    Text(
        stringResource(R.string.login_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
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
