package com.tunedplayer.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/** A tapped heart shows at once, before the save lands, and ends where the database ends. */
@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticSetTest {
    @Test
    fun aTapShowsBeforeTheSaveLands() = runTest(UnconfinedTestDispatcher()) {
        val saved = MutableStateFlow<Collection<Long>>(emptyList())
        val set = OptimisticSet(saved, backgroundScope)
        backgroundScope.launch { set.value.collect {} }
        val slowSave = CompletableDeferred<Unit>()

        set.set(1, on = true) {
            slowSave.await()
            saved.value = listOf(1L)
        }
        assertEquals(setOf(1L), set.value.value)

        slowSave.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(1L), set.value.value)
    }

    @Test
    fun quickOnThenOffEndsOff() = runTest(UnconfinedTestDispatcher()) {
        val saved = MutableStateFlow<Collection<Long>>(emptyList())
        val set = OptimisticSet(saved, backgroundScope)
        backgroundScope.launch { set.value.collect {} }

        set.set(1, on = true) { saved.value = saved.value + 1L }
        set.set(1, on = false) { saved.value = saved.value - 1L }
        advanceUntilIdle()

        assertEquals(emptySet(), set.value.value)
        assertEquals(emptyList(), saved.value.toList())
    }

    @Test
    fun aFailedSaveGoesBackToTheTruth() = runTest(UnconfinedTestDispatcher()) {
        val saved = MutableStateFlow<Collection<Long>>(emptyList())
        val set = OptimisticSet(saved, backgroundScope)
        backgroundScope.launch { set.value.collect {} }

        set.set(1, on = true) { error("disk full") }
        advanceUntilIdle()

        assertEquals(emptySet(), set.value.value)
    }
}
