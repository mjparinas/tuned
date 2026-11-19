package app.tuned.screenshots

import app.tuned.R
import app.tuned.core.Episodes
import app.tuned.data.StartTab
import app.tuned.ui.DownloadsRoute
import app.tuned.ui.EventsRoute
import app.tuned.ui.GuideRoute
import app.tuned.ui.HomeRoute
import app.tuned.ui.MovieRoute
import app.tuned.ui.ProfilesRoute
import app.tuned.ui.SearchRoute
import app.tuned.ui.ShowRoute
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home and its tabs, search, details, the guide, Live Events, Downloads and profiles. */
@RunWith(RobolectricTestRunner::class)
class BrowseScreenshots : Screenshots() {
    @Test fun liveTv() = shot("browse-live-tv", HomeRoute, wait = "Maple News 24")

    @Test
    @Config(qualifiers = TV)
    fun liveTvOnTv() = shot("browse-live-tv-tv", HomeRoute, wait = "Maple News 24")

    @Test fun liveTvLight() = shot("browse-live-tv-light", HomeRoute, wait = "Maple News 24", settings = LIGHT)

    @Test fun movies() = shot("browse-movies", HomeRoute, wait = "Glass Harbour", settings = DARK.copy(startTab = StartTab.MOVIES))

    @Test fun shows() = shot("browse-shows", HomeRoute, wait = "Snowline", settings = DARK.copy(startTab = StartTab.SHOWS))

    @Test
    @Config(qualifiers = TABLET)
    fun liveTvTablet() = shot("browse-live-tv-tablet", HomeRoute, wait = "Maple News 24")

    @Test fun show() = shot("browse-show", ShowRoute(Episodes.showKey("Harbour Town"), "Harbour Town"), wait = "Harbour Town")

    @Test fun movie() = shot("browse-movie", MovieRoute(SampleData.MOVIE_IDS), wait = "Northern Lights")

    @Test fun guide() = shot("browse-guide", GuideRoute(1), wait = "Maple News 24")

    @Test fun search() = shot("browse-search", SearchRoute("hockey"), wait = "Hockey Night")

    @Test fun events() = shot("browse-events", EventsRoute, wait = text(R.string.events_title))

    @Test fun downloads() = shot("browse-downloads", DownloadsRoute, wait = text(R.string.downloads))

    /** Downloads from the bar on Home, which stays below it. */
    @Test
    fun downloadsOnHome() =
        shot("browse-downloads-home", HomeRoute, wait = "Maple News 24") {
            compose.onNode(hasText(text(R.string.downloads)) and isSelectable()).performClick()
        }

    @Test fun profiles() = shot("browse-profiles", ProfilesRoute(atStart = true), wait = text(R.string.profiles_title))
}
