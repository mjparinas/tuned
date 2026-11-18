package app.tuned.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.tuned.ui.settings.CaptionSettingsScreen
import app.tuned.ui.settings.ChannelHealthScreen
import app.tuned.ui.settings.DeveloperLogScreen
import app.tuned.ui.settings.FavouriteOrderScreen
import app.tuned.ui.settings.HiddenGroupsScreen
import app.tuned.ui.settings.RegionSettingsScreen
import app.tuned.ui.settings.SettingsPage
import app.tuned.ui.settings.SettingsPageScreen
import app.tuned.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable data object SettingsRoute

/** One page of Settings, by its [app.tuned.ui.settings.SettingsPage] name. */
@Serializable data class SettingsPageRoute(
    val page: String,
)

@Serializable data object CaptionSettingsRoute

@Serializable data object RegionSettingsRoute

@Serializable data object HiddenGroupsRoute

@Serializable data object ChannelHealthRoute

@Serializable data object FavouriteOrderRoute

/** Settings › Updates and advanced › Logs. */
@Serializable data object DeveloperLogRoute

/** Settings, its pages and the screens they open. */
fun NavGraphBuilder.settingsScreens(app: AppNav) {
    composable<SettingsRoute> {
        SettingsScreen(settings = app.settings, onBack = { app.nav.popBackStack() }, onOpen = { route -> app.nav.navigate(route) })
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
    composable<DeveloperLogRoute> { DeveloperLogScreen(onBack = { app.nav.popBackStack() }) }
}
