package com.tunedplayer.app.ui

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.tunedplayer.app.ui.settings.CaptionSettingsScreen
import com.tunedplayer.app.ui.settings.ChannelHealthScreen
import com.tunedplayer.app.ui.settings.DeveloperLogScreen
import com.tunedplayer.app.ui.settings.FavouriteOrderScreen
import com.tunedplayer.app.ui.settings.HiddenGroupsScreen
import com.tunedplayer.app.ui.settings.ManageListsScreen
import com.tunedplayer.app.ui.settings.RegionSettingsScreen
import com.tunedplayer.app.ui.settings.SettingsPage
import com.tunedplayer.app.ui.settings.SettingsPageScreen
import com.tunedplayer.app.ui.settings.SettingsScreen
import com.tunedplayer.app.ui.settings.SettingsViewModel
import com.tunedplayer.app.ui.settings.TvScreenCalibrateScreen
import kotlinx.serialization.Serializable

@Serializable data object SettingsRoute

/** One page of Settings, by its [com.tunedplayer.app.ui.settings.SettingsPage] name. */
@Serializable data class SettingsPageRoute(
    val page: String,
)

@Serializable data object CaptionSettingsRoute

@Serializable data object RegionSettingsRoute

@Serializable data object HiddenGroupsRoute

@Serializable data object ChannelHealthRoute

@Serializable data object FavouriteOrderRoute

@Serializable data object ManageListsRoute

/** Settings › Updates and advanced › Logs. */
@Serializable data object DeveloperLogRoute

/** Settings › Appearance › Calibrate screen (Android TV). */
@Serializable data object TvScreenCalibrateRoute

/** Settings, its pages and the screens they open. */
fun NavGraphBuilder.settingsScreens(app: AppNav) {
    composable<SettingsRoute> {
        SettingsScreen(
            settings = app.settings,
            onBack = { app.nav.popBackStack() },
            onOpen = { route -> app.nav.navigate(route) },
            onSignedOut = app.openWelcome,
        )
    }
    composable<SettingsPageRoute> { entry ->
        SettingsPageScreen(
            page = SettingsPage.valueOf(entry.toRoute<SettingsPageRoute>().page),
            settings = app.settings,
            onBack = { app.nav.popBackStack() },
            onOpen = { route -> app.nav.navigate(route) },
            onSignedOut = app.openWelcome,
        )
    }
    composable<CaptionSettingsRoute> {
        CaptionSettingsScreen(settings = app.settings, onBack = { app.nav.popBackStack() })
    }
    composable<RegionSettingsRoute> {
        RegionSettingsScreen(settings = app.settings, onBack = { app.nav.popBackStack() })
    }
    composable<HiddenGroupsRoute> {
        HiddenGroupsScreen(settings = app.settings, onBack = { app.nav.popBackStack() })
    }
    composable<ChannelHealthRoute> {
        ChannelHealthScreen(settings = app.settings, onBack = { app.nav.popBackStack() })
    }
    composable<FavouriteOrderRoute> { FavouriteOrderScreen(onBack = { app.nav.popBackStack() }) }
    composable<ManageListsRoute> { ManageListsScreen(onBack = { app.nav.popBackStack() }) }
    composable<DeveloperLogRoute> { DeveloperLogScreen(onBack = { app.nav.popBackStack() }) }
    composable<TvScreenCalibrateRoute> {
        val vm: SettingsViewModel = viewModel()
        TvScreenCalibrateScreen(
            settings = app.settings,
            onBack = { app.nav.popBackStack() },
            onChange = { change -> vm.update(change) },
        )
    }
}
