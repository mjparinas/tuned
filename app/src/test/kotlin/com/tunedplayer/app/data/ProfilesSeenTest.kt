package com.tunedplayer.app.data

import com.tunedplayer.app.data.db.ProfileEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Who's watching?" draws from this on a cold start, before the database answers (docs/decisions/speed.md). */
class ProfilesSeenTest {
    @Test
    fun theProfilesComeBackAsTheyWere() {
        val profiles =
            listOf(
                ProfileEntity(id = 1, name = "", colour = 0, kids = false, pinHash = null, createdAt = 10),
                ProfileEntity(id = 4, name = "Ava", colour = 3, kids = true, pinHash = "salt:hash", createdAt = 20, avatar = "fox"),
            )
        assertEquals(profiles, ProfilesSeen.decode(ProfilesSeen.encode(profiles)))
    }

    @Test
    fun nothingSavedMeansWaitForTheDatabase() {
        assertTrue(ProfilesSeen.decode("").isEmpty())
    }
}
