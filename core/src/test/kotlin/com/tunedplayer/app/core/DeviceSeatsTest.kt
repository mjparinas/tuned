package com.tunedplayer.app.core

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

class AccountSeatsTest {
    @Test
    fun `seats match the Premium kind`() {
        assertEquals(AccountSeats.SEAT_ONCE, AccountSeats.seatProduct("lifetime"))
        assertEquals(AccountSeats.SEAT_YEARLY, AccountSeats.seatProduct("yearly"))
        assertEquals(null, AccountSeats.seatProduct(null))
    }

    @Test
    fun `only active and full count as answers`() {
        val devices = listOf(SeatDevice("a", "TV", 0, current = true))
        assertEquals(SeatAnswer.Active(devices), AccountSeats.answer("active", devices))
        assertEquals(SeatAnswer.Full(devices), AccountSeats.answer("full", devices))
        assertEquals(SeatAnswer.Unreachable, AccountSeats.answer("none", devices))
    }

    @Test
    fun `play sells any number of one-time seats but one yearly seat`() {
        assertEquals(7, AccountSeats.playCanBuy(AccountSeats.SEAT_ONCE, 7, ownsYearlySeat = false))
        assertEquals(1, AccountSeats.playCanBuy(AccountSeats.SEAT_YEARLY, 7, ownsYearlySeat = false))
        assertEquals(0, AccountSeats.playCanBuy(AccountSeats.SEAT_YEARLY, 7, ownsYearlySeat = true))
        assertEquals(0, AccountSeats.playCanBuy(AccountSeats.SEAT_ONCE, 0, ownsYearlySeat = false))
    }
}
