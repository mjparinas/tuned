package app.tuned.ui

import androidx.navigation.NavHostController
import app.tuned.data.AppSettings
import app.tuned.data.CastMember

/** What every screen's navigation needs: the controller, the settings in use, and the moves several screens share. */
class AppNav(
    val nav: NavHostController,
    val settings: AppSettings,
    val play: (Long) -> Unit,
    val tune: (String) -> Unit,
    val openPerson: (CastMember) -> Unit,
    val openHome: () -> Unit,
    val openWelcome: () -> Unit,
)
