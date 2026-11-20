package com.tunedplayer.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.CatchUp
import com.tunedplayer.app.core.M3uCatchUp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** A plain M3U channel's catch-up is found again by its link, with its template, and its days drive the guide. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatchUpDaoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun close() = db.close()

    @Test
    fun m3uChannelsAreFoundByTheirLink() = runTest {
        val link = "http://h/ch/index.m3u8"
        val spec = M3uCatchUp(M3uCatchUp.FLUSSONIC, 3, null)
        db.catchUp().insertAll(listOf(CatchUpEntity(CatchUp.key(link), spec.days, spec.encode()), CatchUpEntity("42", 7)))

        assertEquals(
            M3uCatchUp(M3uCatchUp.FLUSSONIC, 0, null),
            db
                .catchUp()
                .get(CatchUp.key(link))
                ?.source
                ?.let(M3uCatchUp::decode),
        )
        assertEquals(3, db.catchUp().days(CatchUp.key(link)).first())
        assertEquals(7, db.catchUp().days(CatchUp.key("http://h/live/u/p/42.ts")).first(), "Xtream channels still match by stream id")
    }
}
