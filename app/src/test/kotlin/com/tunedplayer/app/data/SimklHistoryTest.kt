package com.tunedplayer.app.data

import com.tunedplayer.app.core.Trakt
import kotlin.test.Test
import kotlin.test.assertEquals

class SimklHistoryTest {
    @Test
    fun readsCompletedFilmsAtTheirLastWatchedTime() {
        val text =
            """{"movies":[
              {"last_watched_at":"2014-04-12T00:30:24Z","status":"completed","movie":{"title":"Inception","year":2010,"ids":{"simkl":1}}},
              {"last_watched_at":null,"status":"completed","movie":{"title":"Heat","year":null}},
              {"status":"completed","movie":{"year":1999}}
            ]}"""
        assertEquals(
            listOf(Trakt.WatchedFilm("Inception", 2010, 1_397_262_624_000L), Trakt.WatchedFilm("Heat", null, 7L)),
            SimklHistory.films(text, nowMs = 7),
        )
    }

    @Test
    fun emptyAnswersAreNoItems() {
        assertEquals(emptyList(), SimklHistory.films("{}", nowMs = 0))
        assertEquals(emptyList(), SimklHistory.films("null", nowMs = 0))
        assertEquals(emptyList(), SimklHistory.episodes("", nowMs = 0))
    }

    @Test
    fun readsEveryWatchedEpisodeFallingBackToTheShowsTime() {
        val text =
            """{"shows":[{"last_watched_at":"2014-04-12T00:30:24Z","status":"watching",
              "show":{"title":"Breaking Bad","year":2008},
              "seasons":[{"number":1,"episodes":[{"number":1,"watched_at":"1970-01-01T00:00:01Z"},{"number":2}]},
                         {"number":2,"episodes":[{"number":5,"watched_at":"bad"}]}]},
              {"status":"plantowatch","show":{"title":"Nothing seen"}}]}"""
        assertEquals(
            listOf(
                Trakt.WatchedEpisode("Breaking Bad", 2008, 1, 1, 1_000L),
                Trakt.WatchedEpisode("Breaking Bad", 2008, 1, 2, 1_397_262_624_000L),
                Trakt.WatchedEpisode("Breaking Bad", 2008, 2, 5, 9L),
            ),
            SimklHistory.episodes(text, nowMs = 9),
        )
    }

    @Test
    fun animeUsesItsTvdbSeasonAndNumber() {
        val text =
            """{"anime":[{"show":{"title":"Attack on Titan","year":2013},
              "seasons":[{"number":1,"episodes":[{"number":26,"tvdb":{"season":2,"episode":1}},{"number":27}]}]}]}"""
        assertEquals(
            listOf(Trakt.WatchedEpisode("Attack on Titan", 2013, 2, 1, 3L), Trakt.WatchedEpisode("Attack on Titan", 2013, 1, 27, 3L)),
            SimklHistory.episodes(text, nowMs = 3),
        )
    }

    @Test
    fun matchesThePlaylistLikeTrakt() {
        val text = """{"movies":[{"last_watched_at":"1970-01-01T00:00:02Z","movie":{"title":"Inception","year":2010}}]}"""
        val local = listOf(Trakt.localFilm(4, "EN - Inception (2010) [4K]", null))
        assertEquals(listOf(Trakt.Match(4, 2_000L)), Trakt.matchFilms(local, SimklHistory.films(text, nowMs = 0)))
    }
}
