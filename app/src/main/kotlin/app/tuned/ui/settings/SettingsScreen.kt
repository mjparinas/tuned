package app.tuned.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.BuildConfig
import app.tuned.R
import app.tuned.container
import app.tuned.data.AppSettings
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.common.BackTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(
    app: Application,
) : AndroidViewModel(app),
    PinActions {
    private val container = app.container

    fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launchSafely("Settings") { container.settings.update(change) }
    }

    val hasPin = container.settings.hasAdultPin.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** How many profiles there are, for the Profiles row. */
    val profileCount =
        container.profiles.profiles
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    override suspend fun setPin(pin: String) = container.settings.setAdultPin(pin)

    override suspend fun unlock(pin: String): PinResult {
        val throttle = container.profiles.throttle
        if (throttle.waiting()) return PinResult.TooMany
        return throttle.record(container.settings.unlockAdult(pin)).toPinResult()
    }

    fun lockAdult() = container.settings.lockAdult()

    override fun matchesLogin(secret: String): Boolean = container.credentials.matches(secret)

    fun refreshNow() = container.scheduler.refreshNow()

    fun clearHistory() {
        viewModelScope.launchSafely("Settings") { container.db.library().clearHistory() }
    }

    /** Signing out forgets the login, the cached playlist and guide, and stops background refreshes. */
    fun signOut(onDone: () -> Unit) {
        viewModelScope.launchSafely("Settings") {
            container.signOut()
            onDone()
        }
    }
}

/**
 * Settings, one section per area. Each section is its own file (SettingsAccount.kt, SettingsPlayback.kt and so on), so
 * features in different areas never edit the same lines. A row that opens another screen passes its route to [onOpen].
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpen: (route: Any) -> Unit,
    onSignedOut: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings), onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            AccountSettings(settings, vm, onOpen, onSignedOut)
            ContentSettings(settings, vm, onOpen)
            BackgroundSettings(settings, vm)
            PlaybackSettings(settings, vm, onOpen)
            TuneSettings(settings, vm)
            AppearanceSettings(settings, vm)
            RatingsSettings(settings, vm)
            AdvancedSettings(settings, vm)
            DeveloperSettings(settings, vm, onOpen)
            HorizontalDivider(Modifier.padding(top = 16.dp))
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
