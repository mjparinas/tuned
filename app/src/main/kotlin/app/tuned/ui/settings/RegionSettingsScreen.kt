package app.tuned.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Row
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
import app.tuned.R
import app.tuned.container
import app.tuned.core.Regions
import app.tuned.data.AppSettings
import app.tuned.data.db.RegionCount
import app.tuned.ui.common.BackTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
        viewModelScope.launch { container.settings.update { it.copy(hiddenRegions = hidden) } }
    }
}

/**
 * Settings › Countries: one switch per country (or region tag) found in the playlist, with how many streams it has.
 * Turning one off hides its channels, movies and shows everywhere, including search.
 */
@Composable
fun RegionSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    vm: RegionViewModel = viewModel(),
) {
    val regions by vm.regions.collectAsStateWithLifecycle()
    val hidden = settings.hiddenRegions
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
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Text(
                    stringResource(R.string.regions_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(regions, key = { it.region }) { r ->
                SwitchRow(
                    title = "${Regions.displayName(r.region)} (${r.region})",
                    checked = r.region !in hidden,
                    summary = pluralStringResource(R.plurals.streams, r.count, r.count),
                ) { show -> vm.setHidden(if (show) hidden - r.region else hidden + r.region) }
            }
        }
    }
}
