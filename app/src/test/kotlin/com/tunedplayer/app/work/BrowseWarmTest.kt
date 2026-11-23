package com.tunedplayer.app.work

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.TunedDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Speculative warm reads the first browse pages from Room so Home and tab switches do not wait on cold SQLite
 * (docs/decisions/speed.md › Speculative warm).
 */
@RunWith(RobolectricTestRunner::class)
class BrowseWarmTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db =
        Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun warmArtReadsFirstLiveAndMovieLogos() = runBlocking {
        db.items().insertAll(
            listOf(
                item(1, ContentKind.LIVE, "News", "http://logo/live.png", 0),
                item(2, ContentKind.MOVIE, "Film", "http://logo/film.png", 0),
                item(3, ContentKind.MOVIE, "Other", null, 1),
            ),
        )
        val live = db.browseWarm().warmArt(ContentKind.LIVE.code, BrowseWarm.PAGE)
        val movies = db.browseWarm().warmArt(ContentKind.MOVIE.code, BrowseWarm.PAGE)
        assertEquals(listOf("http://logo/live.png"), live.map { it.logo })
        assertEquals(listOf("http://logo/film.png", null), movies.map { it.logo })
        assertEquals(listOf("Film", "Other"), movies.map { it.name })
    }

    @Test
    fun warmShowArtReadsOneRowPerShow() = runBlocking {
        db.shows().deleteShowGroups()
        // ShowGroups inserts via upsert path aren't on ShowDao; write entities through openHelper-free Room entity API.
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO show_groups (showKey, groupName, region, adult, showName, logo, episodes, sortOrder, added, sortTitle) " +
                "VALUES ('s1', 'Drama', NULL, 0, 'Show One', 'http://logo/s1.png', 2, 0, 0, 'show one'), " +
                "('s1', 'Drama', NULL, 0, 'Show One', 'http://logo/s1.png', 1, 1, 0, 'show one'), " +
                "('s2', 'Comedy', NULL, 0, 'Show Two', NULL, 1, 2, 0, 'show two')",
        )
        val rows = db.browseWarm().warmShowArt(BrowseWarm.PAGE)
        assertEquals(2, rows.size)
        assertEquals("Show One", rows[0].showName)
        assertEquals("http://logo/s1.png", rows[0].logo)
        assertTrue(db.browseWarm().warmShowGroups(BrowseWarm.PAGE).any { it.groupName == "Drama" })
    }

    @Test
    fun pageCapMatchesBrowseWarmConstant() {
        assertEquals(120, BrowseWarm.PAGE)
    }

    private fun item(
        id: Long,
        kind: ContentKind,
        name: String,
        logo: String?,
        sortOrder: Int,
    ) = ItemEntity(
        id = id,
        kind = kind.code,
        name = name,
        searchName = name.lowercase(),
        groupName = "All",
        region = null,
        adult = false,
        logo = logo,
        url = "http://stream/$id",
        tvgId = null,
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = sortOrder,
    )
}
