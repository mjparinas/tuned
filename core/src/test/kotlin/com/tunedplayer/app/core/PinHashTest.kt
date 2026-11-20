package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PinHashTest {
    @Test
    fun theRightPinUnlocks() {
        val stored = PinHash.create("2468")
        assertTrue(PinHash.verify("2468", stored))
        assertFalse(PinHash.verify("2469", stored))
        assertFalse(PinHash.verify("", stored))
    }

    @Test
    fun thePinItselfIsNotStored() {
        val stored = PinHash.create("1234")
        assertFalse("1234" in stored)
        assertNotEquals(stored, PinHash.create("1234"), "a fresh salt each time")
    }

    @Test
    fun onlyFourToEightDigits() {
        assertTrue(PinHash.isValid("0000"))
        assertTrue(PinHash.isValid("12345678"))
        assertFalse(PinHash.isValid("123"))
        assertFalse(PinHash.isValid("12a4"))
        assertFalse(PinHash.isValid("123456789"))
    }

    @Test
    fun aDamagedRecordNeverUnlocks() {
        assertFalse(PinHash.verify("1234", "garbage"))
        assertFalse(PinHash.verify("1234", "v1:!!:!!"))
    }
}
