package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NewRegionsTest {
    @Test
    fun `the first time only remembers what is there`() {
        val settled = NewRegions.settle(setOf("US", "CA", "EX"), emptySet(), setOf("EX"))
        assertEquals(NewRegions.Settled(setOf("EX"), setOf("US", "CA", "EX")), settled)
    }

    @Test
    fun `with most countries hidden a new one starts hidden`() {
        val seen = setOf("US", "CA", "UK", "FR", "DE")
        val settled = NewRegions.settle(seen + "SE", seen, setOf("UK", "FR", "DE"))
        assertEquals(setOf("UK", "FR", "DE", "SE"), settled?.hidden)
        assertEquals(seen + "SE", settled?.seen)
    }

    @Test
    fun `with most countries shown a new one shows`() {
        val seen = setOf("US", "CA", "UK", "FR", "DE")
        val settled = NewRegions.settle(seen + "SE", seen, setOf("FR"))
        assertEquals(setOf("FR"), settled?.hidden)
        assertEquals(seen + "SE", settled?.seen)
    }

    @Test
    fun `nothing new changes nothing`() {
        val seen = setOf("US", "CA")
        assertNull(NewRegions.settle(setOf("US"), seen, setOf("CA")))
        assertNull(NewRegions.settle(emptySet(), emptySet(), emptySet()))
    }
}
