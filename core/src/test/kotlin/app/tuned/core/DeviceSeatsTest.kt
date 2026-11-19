package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSeatsTest {
    private val phone = SeatDevice("a", "Pixel 8", 0, current = true)

    @Test
    fun onlyAFullAnswerLocksADevice() {
        assertTrue(DeviceSeats.unlocked(owned = true, seat = true))
        assertTrue(DeviceSeats.unlocked(owned = true, seat = null))
        assertFalse(DeviceSeats.unlocked(owned = true, seat = false))
    }

    @Test
    fun nothingUnlocksWithoutAPurchase() {
        assertFalse(DeviceSeats.unlocked(owned = false, seat = true))
        assertFalse(DeviceSeats.unlocked(owned = false, seat = null))
    }

    @Test
    fun anUnreachableListKeepsWhatTheDeviceLastHeard() {
        assertEquals(true, DeviceSeats.remember(SeatAnswer.Unreachable, true))
        assertEquals(false, DeviceSeats.remember(SeatAnswer.Unreachable, false))
        assertNull(DeviceSeats.remember(SeatAnswer.Unreachable, null))
    }

    @Test
    fun answersReplaceWhatTheDeviceRemembers() {
        assertEquals(true, DeviceSeats.remember(SeatAnswer.Active(listOf(phone)), false))
        assertEquals(false, DeviceSeats.remember(SeatAnswer.Full(emptyList()), true))
    }
}
