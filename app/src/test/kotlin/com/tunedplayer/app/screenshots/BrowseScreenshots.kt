package com.tunedplayer.app.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.tunedplayer.app.R
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.Episodes
import com.tunedplayer.app.core.PinnedGroups
import com.tunedplayer.app.core.TitleSort
import com.tunedplayer.app.data.StartTab
import com.tunedplayer.app.data.SyncProgress
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
import com.tunedplayer.app.ui.browse.FirstDownload
import com.tunedplayer.app.ui.browse.RefreshBar
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

    /** Live TV's Filters sheet: Type, Quality, On now, Show only (guide, catch-up, watched) and the countries. */
    @Test
    fun liveTvFilters() = shot(
        "browse-live-tv-filters",
        HomeRoute,
        wait = "Maple News 24",
        then = { openFilters() },
        // The sheet is a window of its own, so the picture takes in every window.
        overlay = {},
    )

    /** Several filters chosen: the chip names the first two and how many more. */
    @Test
    fun liveTvFiltersChosen() = shot(
        "browse-live-tv-filters-chosen",
        HomeRoute,
        wait = "Maple News 24",
        then = {
            openFilters()
            for (chip in listOf(R.string.live_filter_uhd, R.string.live_filter_guide, R.string.live_filter_catch_up)) {
                compose.onNode(filterSheet()).performScrollToNode(hasText(text(chip)))
                compose.onNode(hasText(text(chip))).performClick()
            }
            compose.onNode(hasText(text(R.string.live_filters_done))).performClick()
        },
    )

    /** The sheet's list: the one that scrolls and holds its title. */
    private fun filterSheet() = hasScrollAction() and hasAnyDescendant(hasText(text(R.string.live_filters_title)))

    /** Opens the Filters sheet and waits for what it offers, which it reads as it opens. */
    private fun openFilters() {
        compose.onNode(hasText(text(R.string.live_filters))).performClick()
        compose.waitUntil(15_000L) { compose.onAllNodes(filterSheet()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000L) {
            runCatching { compose.onNode(filterSheet()).performScrollToNode(hasText(text(R.string.live_filter_uhd))) }.isSuccess
        }
        compose.onNode(filterSheet()).performScrollToNode(hasText(text(R.string.live_filters_type)))
    }

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

    @Test fun search() = shot("browse-search", SearchRoute("hockey"), wait = "Riverton Hockey Hour")

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

    /**
     * "Who's watching?" on a TV: the glow fills the screen to its edges, the profiles sit inside the overscan margin,
     * and the remote starts on the first one.
     */
    @Test
    @Config(qualifiers = TV)
    fun profilesOnTv() = shot("browse-profiles-tv", ProfilesRoute(atStart = true), wait = text(R.string.profiles_title))

    @Test
    @Config(qualifiers = TV)
    fun profilesOnTvLight() =
        shot("browse-profiles-tv-light", ProfilesRoute(atStart = true), wait = text(R.string.profiles_title), settings = LIGHT)

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

    /** The first download's numbered steps, part way through processing the channels. */
    @Test fun firstDownload() = part("browse-first-download", "Processing channels") { FirstDownloadSample() }

    @Test
    @Config(qualifiers = TV)
    fun firstDownloadTv() = part("browse-first-download-tv", "Processing channels") { FirstDownloadSample() }

    /** Still downloading, from a server that gives no size: what has arrived so far. */
    @Test
    fun firstDownloadNoSize() = part("browse-first-download-no-size", "so far") {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            FirstDownload(SyncProgress(SyncProgress.Step.DownloadPlaylist, 23_700_000, steps = SAMPLE_STEPS))
        }
    }

    /** The line above the lists while a refresh the person started runs. */
    @Test
    fun refreshBar() = part("browse-refresh-bar", "Step 5 of 7") {
        Column {
            RefreshBar(SyncProgress(SyncProgress.Step.DownloadGuide, 6_200_000, 18_500_000, steps = SAMPLE_STEPS))
        }
    }
}

private val SAMPLE_STEPS = SyncProgress.plan(fromFile = false, xtream = true, portal = false, guide = true)

@Composable
private fun FirstDownloadSample() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        FirstDownload(SyncProgress(SyncProgress.Step.ReadPlaylist, 21_600_000, 48_000_000, rows = 12_345, steps = SAMPLE_STEPS))
    }
}
