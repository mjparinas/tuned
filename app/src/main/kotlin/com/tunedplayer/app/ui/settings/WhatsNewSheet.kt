package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.ReleaseNotes
import com.tunedplayer.app.core.ReleaseNotes.Kind
import com.tunedplayer.app.ui.common.StartFocus
import com.tunedplayer.app.ui.common.TvSafeArea
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.isTelevision
import com.tunedplayer.app.ui.common.startFocus
import kotlinx.coroutines.launch

/**
 * What's new in this version (Melvin, 2026-11-19, after Horizon Maps' screenshots): its GitHub release notes as cards,
 * new first, then improved, then fixed, each kind under a labelled pill. Opened from the Version row in Settings only,
 * never by itself, because Tuned has no pop-ups. The notes are fetched when it opens; nothing is stored. A phone or
 * tablet shows it as a bottom sheet; a TV as a full-screen page ([WhatsNewTv]).
 */
@Composable
internal fun WhatsNewSheet(onDismiss: () -> Unit) {
    val updates = LocalContext.current.container.updates
    var attempt by remember { mutableIntStateOf(0) }
    var notes by remember { mutableStateOf<WhatsNewNotes>(WhatsNewNotes.Loading) }
    LaunchedEffect(attempt) {
        notes = WhatsNewNotes.Loading
        notes = updates.releaseNotes()?.let { WhatsNewNotes.Loaded(ReleaseNotes.parse(it)) } ?: WhatsNewNotes.Failed
    }
    WhatsNew(notes, onRetry = { attempt++ }, onDismiss = onDismiss)
}

/** What's new for [notes]: a bottom sheet, or the full-screen page on a TV. */
@Composable
internal fun WhatsNew(
    notes: WhatsNewNotes,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (isTelevision()) {
        WhatsNewTv(notes, onRetry, onDismiss)
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            NotesHeader((notes as? WhatsNewNotes.Loaded)?.changes)
            LazyColumn(
                Modifier.weight(1f, fill = false).padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                notesItems(notes, onRetry) { change, _ -> ChangeCard(change) }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp)) {
                Text(stringResource(R.string.whats_new_done))
            }
        }
    }
}

/**
 * What's new on a TV (Melvin, 2026-11-21: "Dragging handles shouldn't really be on the TV app... what's new should just
 * show the entire set of updates... it should be easy to scroll using the remote"). A bottom sheet opened half way up
 * with a drag handle a remote can't drag, so a TV gets the whole screen instead, inside the overscan margin: the header
 * and Done on the left, always in view, and every change on the right. Each change takes focus with an outline, so
 * Up and Down step through them and the list scrolls with the remote; focus starts on the first. Left reaches Done,
 * and Back closes it too.
 */
@Composable
private fun WhatsNewTv(
    notes: WhatsNewNotes,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            TvSafeArea {
                val hasChanges = (notes as? WhatsNewNotes.Loaded)?.changes?.isNotEmpty() == true
                // Done holds focus while the notes load; once they show, the first change takes it.
                val start = remember(hasChanges) { StartFocus(enabled = true) }
                val list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.width(TV_HEADER_WIDTH).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        NotesHeader((notes as? WhatsNewNotes.Loaded)?.changes)
                        Button(
                            onClick = onDismiss,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .then(if (hasChanges) Modifier else Modifier.startFocus(start)),
                        ) {
                            Text(stringResource(R.string.whats_new_done))
                        }
                    }
                    LazyColumn(
                        Modifier.weight(1f).fillMaxHeight(),
                        state = list,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        notesItems(notes, onRetry) { change, first ->
                            val shape = RoundedCornerShape(20.dp)
                            ChangeCard(
                                change,
                                Modifier
                                    .then(if (first) Modifier.startFocus(start) else Modifier)
                                    // Back on the first change shows its pill above it, which focus alone wouldn't.
                                    .onFocusChanged { if (it.isFocused && first) scope.launch { list.animateScrollToItem(0) } }
                                    .focusRing(shape, MaterialTheme.colorScheme.primary)
                                    // One stop for TalkBack too: the change is read as a whole.
                                    .semantics(mergeDescendants = true) {}
                                    .focusable(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The header card's width on a TV, leaving the rest of a 960 dp screen to the changes. */
private val TV_HEADER_WIDTH = 300.dp

/** The list's rows for [notes]: loading, a failure with Try again, or each kind's pill and its changes. */
private fun LazyListScope.notesItems(
    notes: WhatsNewNotes,
    onRetry: () -> Unit,
    card: @Composable (change: ReleaseNotes.Change, first: Boolean) -> Unit,
) {
    when (notes) {
        WhatsNewNotes.Loading -> {
            item { Loading() }
        }

        WhatsNewNotes.Failed -> {
            item {
                Message(stringResource(R.string.whats_new_failed))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.whats_new_retry)) }
            }
        }

        is WhatsNewNotes.Loaded -> {
            if (notes.changes.isEmpty()) item { Message(stringResource(R.string.whats_new_empty)) }
            val first = notes.changes.firstOrNull()
            notes.changes.groupBy { it.kind }.forEach { (kind, changes) ->
                item(key = "k:$kind") { KindPill(kind) }
                items(changes, key = { "c:${it.kind}:${it.text}" }) { card(it, it === first) }
            }
        }
    }
}

internal sealed interface WhatsNewNotes {
    data object Loading : WhatsNewNotes

    data object Failed : WhatsNewNotes

    data class Loaded(
        val changes: List<ReleaseNotes.Change>,
    ) : WhatsNewNotes
}

/** The coloured card on top: "Tuned · Update", the version, and how many changes of each kind. */
@Composable
private fun NotesHeader(changes: List<ReleaseNotes.Change>?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val on = MaterialTheme.colorScheme.onPrimaryContainer
        Text(
            stringResource(R.string.whats_new_kicker).uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.4.sp),
            color = on,
        )
        Text(
            stringResource(R.string.whats_new_title, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = on,
            modifier = Modifier.semantics { heading() },
        )
        if (changes != null) {
            val counts = ReleaseNotes.counts(changes)
            val new = counts.getValue(Kind.NEW)
            val improved = counts.getValue(Kind.IMPROVED)
            val fixed = counts.getValue(Kind.FIXED)
            Text(
                listOf(
                    pluralStringResource(R.plurals.whats_new_count_new, new, new),
                    pluralStringResource(R.plurals.whats_new_count_improved, improved, improved),
                    pluralStringResource(R.plurals.whats_new_count_fixed, fixed, fixed),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = on,
            )
        }
    }
}

/** The kind's name on a tinted pill: the colour is never the only sign of the kind. */
@Composable
private fun KindPill(kind: Kind) {
    val hue = kind.hue()
    val colour = hue.colour()
    Text(
        stringResource(kind.label()).uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp),
        fontWeight = FontWeight.Bold,
        color = colour,
        modifier =
            Modifier
                .padding(top = 8.dp)
                .background(colour.copy(alpha = 0.16f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .semantics { heading() },
    )
}

@Composable
private fun ChangeCard(
    change: ReleaseNotes.Change,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(change.kind.icon(), change.kind.hue())
        Text(change.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun Loading() {
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        Text(stringResource(R.string.whats_new_loading), modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}

private fun Kind.hue(): BadgeHue = when (this) {
    Kind.NEW -> BadgeHue.GREEN
    Kind.IMPROVED -> BadgeHue.BLUE
    Kind.FIXED -> BadgeHue.AMBER
}

private fun Kind.label(): Int = when (this) {
    Kind.NEW -> R.string.whats_new_kind_new
    Kind.IMPROVED -> R.string.whats_new_kind_improved
    Kind.FIXED -> R.string.whats_new_kind_fixed
}

private fun Kind.icon(): ImageVector = when (this) {
    Kind.NEW -> Icons.Filled.AutoAwesome
    Kind.IMPROVED -> Icons.AutoMirrored.Filled.TrendingUp
    Kind.FIXED -> Icons.Filled.Build
}
