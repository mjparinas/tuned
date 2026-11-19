package app.tuned.screenshots

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.tuned.R
import app.tuned.core.ContentKind
import app.tuned.core.Episodes
import app.tuned.core.PinnedGroups
import app.tuned.data.StartTab
import app.tuned.data.db.CurrentProfileEntity
import app.tuned.data.db.ProfileEntity
import app.tuned.ui.DownloadsRoute
import app.tuned.ui.EventsRoute
import app.tuned.ui.GuideRoute
import app.tuned.ui.HomeRoute
import app.tuned.ui.MovieRoute
import app.tuned.ui.ProfilesRoute
import app.tuned.ui.SearchRoute
import app.tuned.ui.ShowRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home and its tabs, search, details, the guide, Live Events, Downloads and profiles. */
@RunWith(RobolectricTestRunner::class)
class BrowseScreenshots : Screenshots() {
    @Test fun liveTv() = shot("browse-live-tv", HomeRoute, wait = "Maple News 24")

    /** A group pinned to the front of the chips, with its pin showing. */
    @Test
    fun liveTvPinned() = shot(
        "browse-live-tv-pinned",
        HomeRoute,
        wait = "Maple News 24",
        settings = DARK.copy(pinnedGroups = PinnedGroups.toggle("", ContentKind.LIVE.code, "UK | News")),
        // The groups come after Favourites and Recent, off the right edge: scroll the chips to the pinned one.
        then = {
            compose
                .onNode(hasScrollAction() and hasAnyDescendant(hasText(text(R.string.filter_favourites))))
                .performScrollToNode(hasText("News", substring = true))
        },
    )

    /** A chosen group, with Pin to the front and Hide this group as chips of their own beside it. */
    @Test
    fun liveTvGroupChosen() = shot(
        "browse-live-tv-group-chosen",
        HomeRoute,
        wait = "Maple News 24",
        then = {
            compose
                .onNode(hasScrollAction() and hasAnyDescendant(hasText(text(R.string.filter_favourites))))
                .performScrollToNode(hasText("UK | News"))
            compose.onNode(hasText("UK | News")).performClick()
            // Favourites has scrolled off by now, so the row is found by the chosen group instead.
            compose
                .onNode(hasScrollAction() and hasAnyDescendant(hasText("UK | News")))
                .performScrollToNode(hasText(text(R.string.group_hide)))
        },
    )

    @Test
    @Config(qualifiers = TV)
    fun liveTvOnTv() = shot("browse-live-tv-tv", HomeRoute, wait = "Maple News 24")

    @Test fun liveTvLight() = shot("browse-live-tv-light", HomeRoute, wait = "Maple News 24", settings = LIGHT)

    @Test fun movies() = shot("browse-movies", HomeRoute, wait = "Glass Harbour", settings = DARK.copy(startTab = StartTab.MOVIES))

    @Test fun shows() = shot("browse-shows", HomeRoute, wait = "Snowline", settings = DARK.copy(startTab = StartTab.SHOWS))

    /** With more than one profile, the profile button sits beside the search box, as tall as it. */
    @Test
    fun showsWithProfiles() =
        shot("browse-shows-profiles", HomeRoute, wait = "Snowline", settings = DARK.copy(startTab = StartTab.SHOWS, profileCount = 2))

    @Test
    @Config(qualifiers = TV)
    fun liveTvOnTvWithProfiles() =
        shot("browse-live-tv-tv-profiles", HomeRoute, wait = "Maple News 24", settings = DARK.copy(profileCount = 2))

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
    fun downloadsOnHome() = shot(
        "browse-downloads-home",
        HomeRoute,
        wait = "Maple News 24",
        then = { compose.onNode(hasText(text(R.string.downloads)) and isSelectable()).performClick() },
    )

    @Test fun profiles() = shot("browse-profiles", ProfilesRoute(atStart = true), wait = text(R.string.profiles_title))

    /** Two named profiles, one for kids, opened from Home: the names must read clearly on the dark theme. */
    @Test
    fun profilesNamed() {
        runBlocking(Dispatchers.IO) {
            val profiles = app.container.db.profiles()
            val first =
                profiles.insert(ProfileEntity(name = "Alex", colour = 0, kids = false, pinHash = null, createdAt = 0, avatar = "fox"))
            profiles.insert(ProfileEntity(name = "Sam", colour = 1, kids = true, pinHash = null, createdAt = 0))
            profiles.setCurrent(CurrentProfileEntity(profileId = first))
        }
        shot("browse-profiles-named", ProfilesRoute(atStart = false), wait = "Sam")
    }
}
