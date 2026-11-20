package com.tunedplayer.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.HiddenGroups
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.db.GroupCount
import com.tunedplayer.app.data.hiddenCountries
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.common.BackTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HiddenGroupsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container

    /** Every group in each section, hidden or not, so any of them can be switched back on. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val groups: StateFlow<Map<ContentKind, List<GroupCount>>> =
        container.settings.settings
            .filterNotNull()
            .map { it.hiddenCountries().sorted() to it.showAdult }
            .distinctUntilChanged()
            .flatMapLatest { (regions, adult) ->
                val items = container.db.items()
                val lists =
                    ContentKind.entries.map { kind ->
                        val groups =
                            if (kind ==
                                ContentKind.SERIES
                            ) {
                                container.db.shows().showGroupChips(regions, adult)
                            } else {
                                items.groups(kind.code, regions, adult)
                            }
                        groups.map { kind to it }
                    }
                combine(lists) { pairs ->
                    pairs.toMap()
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setHidden(hidden: Set<String>) {
        viewModelScope.launchSafely("HiddenGroups") { container.settings.update { it.copy(hiddenGroups = hidden) } }
    }
}

/**
 * Settings › Hidden groups: every group in Live TV, Movies and Shows with a switch. A group hidden from its chip on
 * the home screen comes back here. Hidden groups stay out of lists, search and channel flipping.
 */
@Composable
fun HiddenGroupsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    vm: HiddenGroupsViewModel = viewModel(),
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val hidden = settings.hiddenGroups
    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.settings_hidden_groups), onBack) {
                TextButton(onClick = { vm.setHidden(emptySet()) }, enabled = hidden.isNotEmpty()) {
                    Text(stringResource(R.string.regions_show_all))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.hidden_groups_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (kind in ContentKind.entries) {
                val list = groups[kind].orEmpty()
                if (list.isEmpty()) continue
                item(key = "h:${kind.code}") { SectionHeader(stringResource(sectionName(kind))) }
                items(list, key = { "${kind.code}:${it.groupName}" }) { g ->
                    val key = HiddenGroups.key(kind.code, g.groupName)
                    SwitchRow(
                        title = g.groupName,
                        checked = key !in hidden,
                        summary = pluralStringResource(R.plurals.streams, g.count, g.count),
                    ) { show -> vm.setHidden(if (show) hidden - key else hidden + key) }
                }
            }
        }
    }
}

private fun sectionName(kind: ContentKind): Int = when (kind) {
    ContentKind.LIVE -> R.string.tab_live
    ContentKind.MOVIE -> R.string.tab_movies
    ContentKind.SERIES -> R.string.tab_shows
}
