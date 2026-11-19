package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfilesTest {
    @Test
    fun kidsCategoriesGoByGroupAndLiveChannelName() {
        assertTrue(KidsContent.isKids("US| KIDS", "Anything", live = true))
        assertTrue(KidsContent.isKids("Family Movies", "Paddington", live = false))
        assertTrue(KidsContent.isKids("Animation", "Toy Story", live = false))
        assertTrue(KidsContent.isKids("US | Entertainment", "US: Cartoon Network HD", live = true))
        assertTrue(KidsContent.isKids("CA | General", "Disney Junior", live = true))
        assertFalse(KidsContent.isKids("US | Entertainment", "US: Family Guy 24/7", live = true))
        assertFalse(KidsContent.isKids("Comedy", "Nickel Boys", live = false))
        assertFalse(KidsContent.isKids("Sports", "TSN 1", live = true))
    }

    @Test
    fun playerRefusesWhatTheListsHide() {
        fun allowed(
            adult: Boolean,
            group: String,
            name: String,
            unlocked: Boolean,
            kids: Boolean,
        ) = PlayGate.allowed(adult, group, name, live = true, showAdult = unlocked, kidsProfile = kids)

        // Adult channels need the parental unlock, and a kids profile never plays them.
        assertFalse(allowed(adult = true, group = "XXX", name = "Adult 1", unlocked = false, kids = false))
        assertTrue(allowed(adult = true, group = "XXX", name = "Adult 1", unlocked = true, kids = false))
        assertFalse(allowed(adult = true, group = "Kids XXX", name = "Adult 1", unlocked = true, kids = true))
        // A kids profile plays kids and family streams only.
        assertTrue(allowed(adult = false, group = "CA | General", name = "Disney Junior", unlocked = false, kids = true))
        assertFalse(allowed(adult = false, group = "Sports", name = "TSN 1", unlocked = false, kids = true))
        assertTrue(allowed(adult = false, group = "Sports", name = "TSN 1", unlocked = false, kids = false))
    }

    @Test
    fun leavingAKidsProfileNeedsAPin() {
        assertEquals(ProfileLock.PARENTAL, ProfileRules.lockFor(fromKids = true, toKids = false, toHasPin = false, parentalPinSet = true))
        assertEquals(ProfileLock.PROFILE, ProfileRules.lockFor(fromKids = true, toKids = false, toHasPin = true, parentalPinSet = true))
        assertEquals(ProfileLock.NONE, ProfileRules.lockFor(fromKids = true, toKids = true, toHasPin = false, parentalPinSet = true))
        assertEquals(ProfileLock.NONE, ProfileRules.lockFor(fromKids = false, toKids = true, toHasPin = false, parentalPinSet = true))
        assertEquals(ProfileLock.NONE, ProfileRules.lockFor(fromKids = false, toKids = false, toHasPin = false, parentalPinSet = true))
        assertEquals(ProfileLock.PROFILE, ProfileRules.lockFor(fromKids = false, toKids = false, toHasPin = true, parentalPinSet = false))
    }

    @Test
    fun fiveWrongPinsWaitThirtySeconds() {
        var now = 0L
        val throttle = PinThrottle(clock = { now })
        repeat(4) { assertEquals(PinThrottle.Result.WRONG, throttle.record(false)) }
        assertEquals(PinThrottle.Result.TOO_MANY, throttle.record(false))
        assertTrue(throttle.waiting())
        now = 29_999
        assertTrue(throttle.waiting())
        now = 30_000
        assertFalse(throttle.waiting())
        assertEquals(PinThrottle.Result.OK, throttle.record(true))
    }

    @Test
    fun atMostFiveProfiles() {
        assertTrue(ProfileRules.canAdd(1))
        assertTrue(ProfileRules.canAdd(4))
        assertFalse(ProfileRules.canAdd(5))
        assertFalse(ProfileRules.canAdd(7))
    }
}
