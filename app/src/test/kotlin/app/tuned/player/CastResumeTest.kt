package app.tuned.player

import com.google.android.gms.cast.MediaStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A cast stopped on the TV doesn't take the next show (Melvin, 2026-11-19). */
class CastResumeTest {
    @Test
    fun aTvStillPlayingOrPausedCarriesOn() {
        assertTrue(CastResume.carriesOn(MediaStatus.PLAYER_STATE_PLAYING))
        assertTrue(CastResume.carriesOn(MediaStatus.PLAYER_STATE_PAUSED))
        assertTrue(CastResume.carriesOn(MediaStatus.PLAYER_STATE_BUFFERING))
        assertTrue(CastResume.carriesOn(MediaStatus.PLAYER_STATE_LOADING))
    }

    @Test
    fun aStoppedTvEndsTheCast() {
        assertFalse(CastResume.carriesOn(MediaStatus.PLAYER_STATE_IDLE))
        assertFalse(CastResume.carriesOn(MediaStatus.PLAYER_STATE_UNKNOWN))
        assertFalse(CastResume.carriesOn(null))
    }
}
