package com.tunedplayer.app.screenshots

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.tunedplayer.app.R
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.Episodes
import com.tunedplayer.app.core.PinnedGroups
import com.tunedplayer.app.core.TitleSort
import com.tunedplayer.app.data.StartTab
import com.tunedplayer.app.data.db.CurrentProfileEntity
import com.tunedplayer.app.data.db.ProfileEntity
import com.tunedplayer.app.ui.DownloadsRoute
import com.tunedplayer.app.ui.EventsRoute
import com.tunedplayer.app.ui.GuideRoute
import com.tunedplayer.app.ui.HomeRoute
import com.tunedplayer.app.ui.MovieRoute
import com.tunedplayer.app.ui.ProfilesRoute
import com.tunedplayer.app.ui.SearchRoute
import com.tunedplayer.app.ui.ShowRoute
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
            // The chips row is the one sideways scroller on Home. Matched by that, not by a chip's text, because the
            // chip it was found by scrolls out of the tree on the way to the group.
            val chips = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
            compose.onNode(chips).performScrollToNode(hasText("UK | News"))
            compose.onNode(hasText("UK | News")).performClick()
            compose.onNode(chips).performScrollToNode(hasText(text(R.string.group_hide)))
        },
    )

    @Test
    @Config(qualifiers = TV)
    fun liveTvOnTv() = shot("browse-live-tv-tv", HomeRoute, wait = "Maple News 24")

    @Test fun liveTvLight() = shot("browse-live-tv-light", HomeRoute, wait = "Maple News 24", settings = LIGHT)

    @Test fun movies() = shot("browse-movies", HomeRoute, wait = "Glass Harbour", settings = DARK.copy(startTab = StartTab.MOVIES))

    @Test fun shows() = shot("browse-shows", HomeRoute, wait = "Snowline", settings = DARK.copy(startTab = StartTab.SHOWS))

    /** Movies' sort, open: the order in use ticked (Melvin, 2026-11-20: "sort by newest first or sort by the rating"). */
    @Test
    fun moviesSortMenu() = shot(
        "browse-movies-sort",
        HomeRoute,
        wait = "Glass Harbour",
        settings = DARK.copy(startTab = StartTab.MOVIES),
        then = { compose.onNode(hasText(text(R.string.sort_chip))).performClick() },
    )

    /** Movies sorted by rating, the chip naming the order. */
    @Test
    fun moviesTopRated() = shot(
        "browse-movies-top-rated",
        HomeRoute,
        wait = "Glass Harbour",
        settings = DARK.copy(startTab = StartTab.MOVIES, movieSort = TitleSort.Order.RATING),
    )

    /** Shows newest first, apart from the Movies sort. */
    @Test
    fun showsRecentlyAdded() = shot(
        "browse-shows-recently-added",
        HomeRoute,
        wait = "Snowline",
        settings = DARK.copy(startTab = StartTab.SHOWS, showSort = TitleSort.Order.ADDED),
    )

    @Test
    @Config(qualifiers = TV)
    fun moviesSortMenuOnTv() = shot(
        "browse-movies-sort-tv",
        HomeRoute,
        // On a TV the grid starts below Continue watching, so its first film is the one on screen.
        wait = "Northern Lights",
        settings = DARK.copy(startTab = StartTab.MOVIES, movieSort = TitleSort.Order.YEAR),
        then = { compose.onNode(hasText(text(R.string.sort_year))).performClick() },
    )

    /** Films filed under "EN" still show with only Canada and the United States on (Melvin, 2026-11-20). */
    @Test
    fun moviesUsCanada() = shot(
        "browse-movies-us-canada",
        HomeRoute,
        wait = "Glass Harbour",
        settings =
            DARK.copy(startTab = StartTab.MOVIES, hiddenRegions = SampleData.allButUsCanada, seenRegions = SampleData.regions),
    )

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

    /** Whole-guide search: later programmes, with the day and time they start and their channel. */
    @Test fun searchGuide() = shot("browse-search-guide", SearchRoute("planet wild"), wait = text(R.string.search_in_guide))

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
