package app.tuned.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.RegionLanguages
import app.tuned.core.Regions
import app.tuned.data.AppSettings
import app.tuned.data.db.RegionCount
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.common.BackTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class RegionViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container

    val regions: StateFlow<List<RegionCount>> =
        container.db
            .items()
            .regions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setHidden(hidden: Set<String>) {
        val known = regions.value.map { it.region }
        viewModelScope.launchSafely("RegionSettings") {
            container.settings.update { it.copy(hiddenRegions = hidden, seenRegions = it.seenRegions + known) }
        }
    }
}

/**
 * Settings › Countries and languages: one switch per language first, then one per country (or region tag) found in
 * the playlist, A to Z by name, with how many streams each has. The search box above filters both as you type.
 * Turning one off hides its channels, movies and shows everywhere, including search. A language is the set of tags
 * that speak it ([RegionLanguages]), so its switch turns all of those on or off together and needs nothing stored of
 * its own; it shows on while any of them is on.
 */
@Composable
fun RegionSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    vm: RegionViewModel = viewModel(),
) {
    val regions by vm.regions.collectAsStateWithLifecycle()
    val hidden = settings.hiddenRegions
    val languages = remember(regions) { RegionLanguages.group(regions.map { it.region }) }
    val counts = remember(regions) { regions.associate { it.region to it.count } }
    val byName = remember(regions) { Regions.sortedByName(regions) { it.region } }
    // Filters as each letter is typed; the lists are small, so nothing waits on the database.
    var query by rememberSaveable { mutableStateOf("") }
    val shownLanguages =
        languages
            .map { (code, tags) -> Triple(code, tags, languageName(code)) }
            .filter { (_, tags, name) -> Regions.matchesSearch(query, tags + name) }
    val shownRegions = byName.filter { Regions.regionMatches(query, it.region) }
    Scaffold(
        topBar = {
            BackTopBar(stringResource(R.string.settings_countries), onBack) {
                Row {
                    TextButton(onClick = { vm.setHidden(emptySet()) }) { Text(stringResource(R.string.regions_show_all)) }
                    TextButton(
                        onClick = { vm.setHidden(regions.map { it.region }.toSet()) },
                    ) { Text(stringResource(R.string.regions_hide_all)) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            RegionSearchField(query) { query = it }
            RegionList(query, shownLanguages, shownRegions, counts, hidden, vm::setHidden)
        }
    }
}

@Composable
private fun RegionSearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.regions_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun RegionList(
    query: String,
    languages: List<Triple<String, List<String>, String>>,
    regions: List<RegionCount>,
    counts: Map<String, Int>,
    hidden: Set<String>,
    onSetHidden: (Set<String>) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (query.isBlank()) {
            item {
                Text(
                    stringResource(R.string.regions_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (query.isNotBlank() && languages.isEmpty() && regions.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.search_nothing, query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (languages.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.regions_languages)) }
            items(languages, key = { "lang:" + it.first }) { (_, tags, name) ->
                val streams = tags.sumOf { counts[it] ?: 0 }
                SwitchRow(
                    title = name,
                    checked = tags.any { it !in hidden },
                    summary =
                        stringResource(
                            R.string.language_summary,
                            tags.joinToString(", "),
                            pluralStringResource(R.plurals.streams, streams, streams),
                        ),
                ) { show -> onSetHidden(if (show) hidden - tags.toSet() else hidden + tags) }
            }
            if (regions.isNotEmpty()) item { SectionHeader(stringResource(R.string.regions_countries)) }
        }
        items(regions, key = { it.region }) { r ->
            SwitchRow(
                title = "${Regions.displayName(r.region)} (${r.region})",
                checked = r.region !in hidden,
                summary = pluralStringResource(R.plurals.streams, r.count, r.count),
            ) { show -> onSetHidden(if (show) hidden - r.region else hidden + r.region) }
        }
    }
}
