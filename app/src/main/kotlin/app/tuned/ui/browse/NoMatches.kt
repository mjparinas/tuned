package app.tuned.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LazyPagingItems
import androidx.paging.LoadState
import app.tuned.R
import app.tuned.core.ContentKind

/**
 * A search on one tab that finds nothing isn't a dead end: one tap runs the same words through Search everything
 * (every tab and the guide). Shows only once the list has loaded empty for a search, never while it loads.
 */
@Composable
internal fun NoMatches(
    items: LazyPagingItems<*>,
    vm: BrowseViewModel,
    kind: ContentKind,
    onSearchEverything: (String) -> Unit,
) {
    val search by vm.search(kind).collectAsStateWithLifecycle()
    val empty = items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading
    if (!empty || search.isBlank()) return
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.no_matches, search.trim()),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = { onSearchEverything(search.trim()) }) {
            Icon(Icons.Filled.TravelExplore, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.no_matches_search_everything))
        }
    }
}
